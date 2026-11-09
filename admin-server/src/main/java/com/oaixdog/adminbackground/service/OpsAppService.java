package com.oaixdog.adminbackground.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.oaixdog.adminbackground.config.ApplicationProperties;
import com.oaixdog.adminbackground.entity.Account;
import com.oaixdog.adminbackground.entity.OpsApp;
import com.oaixdog.adminbackground.entity.OpsHost;
import com.oaixdog.adminbackground.exception.BusinessException;
import com.oaixdog.adminbackground.exception.ErrorCode;
import com.oaixdog.adminbackground.mapper.OpsAppMapper;
import com.oaixdog.adminbackground.mapper.OpsHostMapper;
import com.oaixdog.adminbackground.protocol.OpsAppResponse;
import com.oaixdog.adminbackground.protocol.OpsAppState;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.oaixdog.adminbackground.service.AnsibleRunner.quote;

/**
 * 程序：保存在 ops_app，每条绑定一台主机和它上面的一个目录（自己配置）。名称同时是 systemd 服务名 danmu-&lt;名称&gt;。
 * <p>
 * 服务器上由 systemd 运行程序：服务执行程序目录里的 bin/start.sh --foreground（JVM 参数在 conf/jvm.options），挂了自动拉起、开机自启。
 * 服务文件在第一次启动或发布时由 Ansible 生成。启停、发布、回滚、保存配置在后台执行（{@link OpsTaskService}），立即返回任务 id；
 * 状态、日志、配置文件的读取直接通过 SSH 执行。
 * <p>
 * 查看列表、日志需要 ops_app；新建、编辑、删除需要 ops_app:manage；启停 ops_app:control；发布、回滚 ops_app:deploy；
 * 配置文件（可能有密码）的查看和保存都需要 ops_app:config。
 */
@Service
@RequiredArgsConstructor
public class OpsAppService {

    private static final Logger log = LoggerFactory.getLogger(OpsAppService.class);

    private static final String MENU = "ops_app";
    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    /** conf 下的相对路径 */
    private static final Pattern CONF_FILE = Pattern.compile("[A-Za-z0-9_.\\-]+(/[A-Za-z0-9_.\\-]+)*");
    private static final Pattern BACKUP_NAME = Pattern.compile("\\d{8}-\\d{6}");
    private static final int DIR_MAX_LENGTH = 256;
    private static final int REMARK_MAX_LENGTH = 128;
    private static final Set<String> ACTIONS = Set.of("start", "stop", "restart");
    private static final int LOG_LINES_DEFAULT = 200;
    private static final int LOG_LINES_MAX = 2000;
    /** 程序 logback 写入的日志文件，第一个为默认 */
    private static final List<String> LOG_FILES = List.of("server.log", "error.log");
    private static final long CONF_MAX_SIZE = 1024 * 1024;
    /** 前端每片 2MB，base64 后约 2.7MB，在 HTTP 请求体上限 16MB 以内 */
    private static final int CHUNK_MAX_SIZE = 4 * 1024 * 1024;
    private static final long PACKAGE_MAX_SIZE = 1024L * 1024 * 1024;
    private static final long UPLOAD_EXPIRE_MILLIS = 3_600_000;
    private static final long READ_TIMEOUT_SECONDS = 30;
    private static final long CONTROL_TIMEOUT_SECONDS = 180;
    private static final long DEPLOY_TIMEOUT_SECONDS = 900;

    private final PermissionService permissionService;
    private final ApplicationProperties properties;
    private final AnsibleRunner ansibleRunner;
    private final OpsTaskService opsTaskService;
    private final OpsHostService opsHostService;
    private final OpsHostMapper opsHostMapper;
    private final OpsAppMapper opsAppMapper;

    /** 程序 id → 上一次查询时的 CPU 累计时间，用来算两次查询之间的 CPU 使用率 */
    private final Map<Long, long[]> lastCpu = new ConcurrentHashMap<>();
    /** uploadId → 正在上传的程序包 */
    private final Map<String, Upload> uploads = new ConcurrentHashMap<>();

    private static final class Upload {
        private final long appId;
        private final Path file;
        private int nextIndex;
        private long size;
        private volatile long touched = System.currentTimeMillis();

        private Upload(long appId, Path file) {
            this.appId = appId;
            this.file = file;
        }
    }

    // ---------------------------------------------------------------- 列表、登记

    /** hostId 为空时返回全部程序；每台主机一次 SSH 读出它上面所有程序的状态 */
    public Map<String, Object> list(String authorization, Long hostId) {
        permissionService.requireMenu(authorization, MENU);
        List<OpsApp> apps = opsAppMapper.selectList(Wrappers.<OpsApp>lambdaQuery()
                .eq(hostId != null, OpsApp::getHostId, hostId)
                .orderByAsc(OpsApp::getHostId).orderByAsc(OpsApp::getName).orderByAsc(OpsApp::getId));
        Map<Long, OpsHost> hosts = apps.isEmpty() ? Map.of()
                : opsHostMapper.selectBatchIds(apps.stream().map(OpsApp::getHostId).distinct().toList()).stream()
                .collect(Collectors.toMap(OpsHost::getId, Function.identity()));
        Map<Long, List<OpsApp>> appsByHost = apps.stream().collect(Collectors.groupingBy(OpsApp::getHostId));

        Map<Long, CompletableFuture<Map<Long, OpsAppState>>> statuses = new HashMap<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            appsByHost.forEach((id, hostApps) -> {
                OpsHost host = hosts.get(id);
                if (host != null) {
                    statuses.put(id, CompletableFuture.supplyAsync(() -> status(host, hostApps), executor));
                }
            });
        }

        List<OpsAppResponse> list = new ArrayList<>();
        for (OpsApp app : apps) {
            OpsHost host = hosts.get(app.getHostId());
            OpsAppResponse response = OpsAppResponse.valueOf(app, host);
            response.setBusy(opsTaskService.isBusy(lock(app)));
            CompletableFuture<Map<Long, OpsAppState>> status = statuses.get(app.getHostId());
            if (status != null) {
                try {
                    response.setState(status.join().get(app.getId()));
                    response.setHostOnline(true);
                } catch (RuntimeException exception) {
                    Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                    response.setError(cause.getMessage());
                }
            }
            list.add(response);
        }
        return Map.of("list", list);
    }

    /** 只保存登记；systemd 服务在第一次启动或发布时生成 */
    public OpsAppResponse create(String authorization, String name, Long hostId, String dir, String remark) {
        permissionService.requireMenu(authorization, MENU + ":manage");
        var app = new OpsApp();
        OpsHost host = fill(app, name, hostId, dir, remark);
        long now = System.currentTimeMillis();
        app.setDeployedAt(0L);
        app.setCreatedAt(now);
        app.setUpdatedAt(now);
        requireUnique(app);
        try {
            opsAppMapper.insert(app);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.OPS_APP_NAME_EXISTS);
        }
        return OpsAppResponse.valueOf(app, host);
    }

    /** 修改名称、主机、目录时程序必须已停止；名称或主机变了，删除旧主机上旧名称的服务 */
    public OpsAppResponse update(String authorization, Long appId, String name, Long hostId, String dir, String remark) {
        permissionService.requireMenu(authorization, MENU + ":manage");
        OpsApp app = requireApp(appId);
        OpsApp old = copy(app);
        OpsHost host = fill(app, name, hostId, dir, remark);
        boolean moved = !old.getName().equals(app.getName()) || !old.getHostId().equals(app.getHostId());
        if ((moved || !old.getDir().equals(app.getDir())) && isRunning(old)) {
            throw new BusinessException(ErrorCode.OPS_APP_RUNNING);
        }
        app.setUpdatedAt(System.currentTimeMillis());
        requireUnique(app);
        try {
            opsAppMapper.updateById(app);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.OPS_APP_NAME_EXISTS);
        }
        if (moved) {
            unregister(old);
        }
        return OpsAppResponse.valueOf(app, host);
    }

    /** 程序必须已停止；删除登记和 systemd 服务，不删除程序目录 */
    public void remove(String authorization, Long appId) {
        permissionService.requireMenu(authorization, MENU + ":manage");
        OpsApp app = requireApp(appId);
        if (isRunning(app)) {
            throw new BusinessException(ErrorCode.OPS_APP_RUNNING);
        }
        opsAppMapper.deleteById(app.getId());
        unregister(app);
    }

    // ---------------------------------------------------------------- 运维任务

    public long control(String authorization, Long appId, String action) {
        Account account = permissionService.requireMenu(authorization, MENU + ":control");
        if (action == null || !ACTIONS.contains(action)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        OpsApp app = requireApp(appId);
        OpsHost host = opsHostService.requireHost(app.getHostId());
        String verb = Map.of("start", "启动", "stop", "停止", "restart", "重启").get(action);
        return opsTaskService.submit(account, action, verb + " " + app.getName() + "（" + host.getIp() + "）", host.getId(), app.getId(),
                lock(app), out -> ansibleRunner.playbook(List.of(host), "app_control.yml", vars(app, Map.of("app_action", action)),
                        out, CONTROL_TIMEOUT_SECONDS) == 0);
    }

    public long deploy(String authorization, Long appId, String uploadId, Boolean overwriteConf) {
        Account account = permissionService.requireMenu(authorization, MENU + ":deploy");
        OpsApp app = requireApp(appId);
        OpsHost host = opsHostService.requireHost(app.getHostId());
        Upload upload = uploadId == null ? null : uploads.get(uploadId);
        if (upload == null || upload.appId != app.getId()) {
            throw new BusinessException(ErrorCode.OPS_UPLOAD_NOT_FOUND);
        }
        uploads.remove(uploadId);
        Map<String, Object> vars = vars(app, Map.of("package", upload.file.toAbsolutePath().toString(),
                "overwrite_conf", Boolean.TRUE.equals(overwriteConf), "backup_keep", properties.getOpsBackupKeep()));
        try {
            return opsTaskService.submit(account, "deploy", "发布 " + app.getName() + "（" + host.getIp() + "）", host.getId(), app.getId(),
                    lock(app), out -> {
                        try {
                            out.accept("程序包 " + upload.size + " 字节");
                            boolean success = ansibleRunner.playbook(List.of(host), "app_deploy.yml", vars, out, DEPLOY_TIMEOUT_SECONDS) == 0;
                            if (success) {
                                var deployed = new OpsApp();
                                deployed.setId(app.getId());
                                deployed.setDeployedAt(System.currentTimeMillis());
                                opsAppMapper.updateById(deployed);
                            }
                            return success;
                        } finally {
                            Files.deleteIfExists(upload.file);
                        }
                    });
        } catch (BusinessException exception) {
            deleteQuietly(upload.file);
            throw exception;
        }
    }

    /** 程序目录 backup/ 下的发布备份，新的在前 */
    public Map<String, Object> backups(String authorization, Long appId) {
        permissionService.requireMenu(authorization, MENU + ":deploy");
        OpsApp app = requireApp(appId);
        String output = ansibleRunner.shell(host(app), "ls -1 " + quote(app.getDir() + "/backup") + " 2>/dev/null | sort -r || true",
                READ_TIMEOUT_SECONDS);
        List<String> list = Arrays.stream(output.split("\n")).map(String::trim)
                .filter(it -> BACKUP_NAME.matcher(it).matches()).toList();
        return Map.of("list", list);
    }

    public long rollback(String authorization, Long appId, String backup) {
        Account account = permissionService.requireMenu(authorization, MENU + ":deploy");
        if (backup == null || !BACKUP_NAME.matcher(backup).matches()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        OpsApp app = requireApp(appId);
        OpsHost host = opsHostService.requireHost(app.getHostId());
        return opsTaskService.submit(account, "rollback", "回滚 " + app.getName() + " 到 " + backup + "（" + host.getIp() + "）",
                host.getId(), app.getId(), lock(app), out -> ansibleRunner.playbook(List.of(host), "app_rollback.yml",
                        vars(app, Map.of("backup", backup)), out, DEPLOY_TIMEOUT_SECONDS) == 0);
    }

    // ---------------------------------------------------------------- 日志、配置文件

    public Map<String, Object> log(String authorization, Long appId, Integer lines, String file) {
        permissionService.requireMenu(authorization, MENU);
        OpsApp app = requireApp(appId);
        String name = file == null || file.isBlank() ? LOG_FILES.getFirst() : file;
        if (!LOG_FILES.contains(name)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        int count = Math.clamp(lines == null ? LOG_LINES_DEFAULT : lines, 1, LOG_LINES_MAX);
        String path = quote(app.getDir() + "/logs/" + name);
        String content = ansibleRunner.shell(host(app),
                "if [ -f " + path + " ]; then tail -c 1048576 " + path + " | tail -n " + count + "; fi", READ_TIMEOUT_SECONDS);
        return Map.of("content", content);
    }

    public Map<String, Object> configList(String authorization, Long appId) {
        permissionService.requireMenu(authorization, MENU + ":config");
        OpsApp app = requireApp(appId);
        String conf = quote(app.getDir() + "/conf");
        String output = ansibleRunner.shell(host(app), "if [ -d " + conf + " ]; then cd " + conf + " && find . -type f | sed 's#^./##' | sort; fi",
                READ_TIMEOUT_SECONDS);
        List<String> files = Arrays.stream(output.split("\n")).map(String::trim).filter(it -> !it.isEmpty()).toList();
        return Map.of("files", files);
    }

    public Map<String, Object> configGet(String authorization, Long appId, String file) {
        permissionService.requireMenu(authorization, MENU + ":config");
        OpsApp app = requireApp(appId);
        String path = quote(app.getDir() + "/conf/" + requireConfFile(file));
        String output = ansibleRunner.shell(host(app), "if [ ! -f " + path + " ]; then echo missing; elif [ $(stat -c %s " + path + ") -gt "
                + CONF_MAX_SIZE + " ]; then echo large; else echo ok; base64 -w0 " + path + "; fi", READ_TIMEOUT_SECONDS);
        String[] parts = output.split("\n", 2);
        switch (parts[0].trim()) {
            case "missing" -> throw new BusinessException(ErrorCode.OPS_APP_INVALID, "文件不存在：" + file);
            case "large" -> throw new BusinessException(ErrorCode.OPS_APP_INVALID, "文件超过 1MB，不能在线查看：" + file);
            default -> {
                String encoded = parts.length > 1 ? parts[1].trim() : "";
                return Map.of("content", new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8));
            }
        }
    }

    /** 保存时旧文件备份到程序目录的 backup-conf/；restart 为 true 时保存后重启 */
    public long configSave(String authorization, Long appId, String file, String content, Boolean restart) {
        Account account = permissionService.requireMenu(authorization, MENU + ":config");
        boolean restartAfter = Boolean.TRUE.equals(restart);
        if (restartAfter) {
            permissionService.requireMenu(authorization, MENU + ":control");
        }
        OpsApp app = requireApp(appId);
        OpsHost host = opsHostService.requireHost(app.getHostId());
        String name = requireConfFile(file);
        byte[] bytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > CONF_MAX_SIZE) {
            throw new BusinessException(ErrorCode.OPS_APP_INVALID, "文件不能超过 1MB");
        }
        Map<String, Object> vars = vars(app, Map.of("file", name, "content_b64", Base64.getEncoder().encodeToString(bytes),
                "restart", restartAfter));
        String title = "保存 " + app.getName() + " 的 conf/" + name + (restartAfter ? " 并重启" : "") + "（" + host.getIp() + "）";
        return opsTaskService.submit(account, "config", title, host.getId(), app.getId(), lock(app),
                out -> ansibleRunner.playbook(List.of(host), "app_config_save.yml", vars, out, CONTROL_TIMEOUT_SECONDS) == 0);
    }

    // ---------------------------------------------------------------- 上传

    /** 程序包先分片上传到管理后台本机的 ops.upload.dir，发布时再由 Ansible 复制到服务器；1 小时没有新分片的上传被删除 */
    public Map<String, Object> uploadStart(String authorization, Long appId) {
        permissionService.requireMenu(authorization, MENU + ":deploy");
        OpsApp app = requireApp(appId);
        cleanExpiredUploads();
        Path dir = Path.of(properties.getOpsUploadDir());
        String id = UUID.randomUUID().toString().replace("-", "");
        try {
            Files.createDirectories(dir);
            Path file = Files.createFile(dir.resolve(id + ".zip"));
            uploads.put(id, new Upload(app.getId(), file));
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.OPS_APP_INVALID, "创建上传文件失败：" + e.getMessage());
        }
        return Map.of("uploadId", id);
    }

    /** data 为这一片的 base64，index 从 0 开始按顺序发送 */
    public void uploadChunk(String authorization, Long appId, String uploadId, Integer index, String data) {
        permissionService.requireMenu(authorization, MENU + ":deploy");
        if (uploadId == null || index == null || index < 0 || data == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        Upload upload = uploads.get(uploadId);
        if (upload == null || appId == null || upload.appId != appId) {
            throw new BusinessException(ErrorCode.OPS_UPLOAD_NOT_FOUND);
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(data);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        if (bytes.length > CHUNK_MAX_SIZE) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        synchronized (upload) {
            if (index == upload.nextIndex - 1) {
                return;  // 应答丢失后重发的同一片
            }
            if (index != upload.nextIndex) {
                throw new BusinessException(ErrorCode.OPS_APP_INVALID, "分片顺序错误：期望 " + upload.nextIndex + "，收到 " + index);
            }
            if (upload.size + bytes.length > PACKAGE_MAX_SIZE) {
                uploads.remove(uploadId);
                deleteQuietly(upload.file);
                throw new BusinessException(ErrorCode.OPS_APP_INVALID, "程序包不能超过 1GB");
            }
            try {
                Files.write(upload.file, bytes, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new BusinessException(ErrorCode.OPS_APP_INVALID, "写入上传文件失败：" + e.getMessage());
            }
            upload.size += bytes.length;
            upload.nextIndex++;
            upload.touched = System.currentTimeMillis();
        }
    }

    private void cleanExpiredUploads() {
        long expireBefore = System.currentTimeMillis() - UPLOAD_EXPIRE_MILLIS;
        uploads.forEach((id, upload) -> {
            if (upload.touched < expireBefore && uploads.remove(id) != null) {
                deleteQuietly(upload.file);
            }
        });
        // 管理后台重启后没有登记的残留文件
        Path dir = Path.of(properties.getOpsUploadDir());
        if (Files.isDirectory(dir)) {
            Set<Path> active = uploads.values().stream().map(it -> it.file.toAbsolutePath()).collect(Collectors.toSet());
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(it -> !active.contains(it.toAbsolutePath())).forEach(it -> {
                    try {
                        if (Files.getLastModifiedTime(it).toMillis() < expireBefore) {
                            Files.deleteIfExists(it);
                        }
                    } catch (IOException e) {
                        log.debug("删除过期上传文件失败，file={}", it, e);
                    }
                });
            } catch (IOException e) {
                log.debug("清理上传目录失败", e);
            }
        }
    }

    // ---------------------------------------------------------------- 状态

    /** 一次 SSH 读出这台主机上这些程序的状态，程序 id → 状态 */
    private Map<Long, OpsAppState> status(OpsHost host, List<OpsApp> apps) {
        StringBuilder script = new StringBuilder();
        for (OpsApp app : apps) {
            String dir = quote(app.getDir());
            String unit = quote("danmu-" + app.getName());
            String marker = quote("-Dhost.app.dir=" + app.getDir() + " ");
            script.append("echo @app=").append(app.getId()).append('\n')
                    .append("systemctl show ").append(unit)
                    .append(" -p LoadState -p ActiveState -p MainPID -p NRestarts -p MemoryCurrent -p CPUUsageNSec 2>/dev/null\n")
                    .append("pid=$(systemctl show ").append(unit).append(" -p MainPID --value 2>/dev/null)\n")
                    .append("if [ \"${pid:-0}\" != 0 ]; then echo \"Elapsed=$(ps -o etimes= -p $pid | tr -d ' ')\"; fi\n")
                    .append("if ls ").append(dir).append("/lib/*.jar >/dev/null 2>&1; then echo Deployed=1; fi\n")
                    // 不归 systemd 管、用 bin/start.sh 在后台启动的进程
                    .append("old=$(cat ").append(dir).append("/app.pid 2>/dev/null | tr -dc 0-9)\n")
                    .append("if [ -n \"$old\" ] && [ \"$old\" != \"${pid:-0}\" ] && ps -ww -o args= -p \"$old\" 2>/dev/null | grep -qF -- ")
                    .append(marker).append("; then echo \"Unmanaged=$old\"; echo \"UnmanagedElapsed=$(ps -o etimes= -p $old | tr -d ' ')\"; ")
                    .append("echo \"UnmanagedRss=$(ps -o rss= -p $old | tr -d ' ')\"; fi\n");
        }
        String output = ansibleRunner.shell(host, script.toString(), READ_TIMEOUT_SECONDS);
        Map<Long, Map<String, String>> values = new HashMap<>();
        Map<String, String> current = null;
        for (String line : output.split("\n")) {
            if (line.startsWith("@app=")) {
                current = new HashMap<>();
                values.put(Long.parseLong(line.substring(5).trim()), current);
            } else if (current != null && line.indexOf('=') > 0) {
                current.put(line.substring(0, line.indexOf('=')), line.substring(line.indexOf('=') + 1).trim());
            }
        }
        long now = System.currentTimeMillis();
        int cores = opsHostService.cpuCores(host.getId());
        Map<Long, OpsAppState> states = new HashMap<>();
        for (OpsApp app : apps) {
            Map<String, String> v = values.getOrDefault(app.getId(), Map.of());
            var state = new OpsAppState();
            state.setDir(app.getDir());
            state.setError("");
            state.setDeployed(v.containsKey("Deployed"));
            boolean loaded = "loaded".equals(v.get("LoadState"));
            state.setStatus(loaded ? v.getOrDefault("ActiveState", "unknown") : "not-found");
            state.setRestarts(parseLong(v.get("NRestarts")));
            long pid = parseLong(v.get("MainPID"));
            if ("active".equals(state.getStatus()) && pid > 0) {
                state.setRunning(true);
                state.setPid(pid);
                state.setStartTime(now - parseLong(v.get("Elapsed")) * 1000);
                long memory = parseLong(v.get("MemoryCurrent"));
                state.setMemory(memory > 0 && memory < Long.MAX_VALUE / 2 ? memory : 0);
                long cpu = parseLong(v.get("CPUUsageNSec"));
                long[] last = lastCpu.put(app.getId(), new long[]{cpu, now, pid});
                if (last != null && last[2] == pid && now > last[1] && cpu >= last[0]) {
                    state.setCpuUsage((cpu - last[0]) / 1e6 / (now - last[1]) / cores);
                }
            } else if (v.containsKey("Unmanaged")) {
                state.setRunning(true);
                state.setUnmanaged(true);
                state.setPid(parseLong(v.get("Unmanaged")));
                state.setStartTime(now - parseLong(v.get("UnmanagedElapsed")) * 1000);
                state.setMemory(parseLong(v.get("UnmanagedRss")) * 1024);
            } else {
                lastCpu.remove(app.getId());
            }
            states.put(app.getId(), state);
        }
        return states;
    }

    private boolean isRunning(OpsApp app) {
        OpsHost host = opsHostMapper.selectById(app.getHostId());
        if (host == null) {
            return false;
        }
        try {
            OpsAppState state = status(host, List.of(app)).get(app.getId());
            return state != null && state.isRunning();
        } catch (BusinessException exception) {
            // 连不上主机时不阻止删除或修改登记
            log.warn("读取程序状态失败，按没有运行处理，app={}，{}", app.getName(), exception.getMessage());
            return false;
        }
    }

    /** 删除服务，失败时只记日志（例如主机已经下线） */
    private void unregister(OpsApp app) {
        OpsHost host = opsHostMapper.selectById(app.getHostId());
        if (host == null) {
            return;
        }
        StringBuilder output = new StringBuilder();
        try {
            int code = ansibleRunner.playbook(List.of(host), "app_unregister.yml", Map.of("app_name", app.getName()),
                    line -> output.append(line).append('\n'), 60);
            if (code != 0) {
                log.warn("删除 systemd 服务失败，app={}，输出：{}", app.getName(), output);
            }
        } catch (BusinessException exception) {
            log.warn("删除 systemd 服务失败，app={}，{}", app.getName(), exception.getMessage());
        }
    }

    // ---------------------------------------------------------------- 工具

    private OpsApp requireApp(Long appId) {
        OpsApp app = appId == null ? null : opsAppMapper.selectById(appId);
        if (app == null) {
            throw new BusinessException(ErrorCode.OPS_APP_NOT_FOUND);
        }
        return app;
    }

    private OpsHost host(OpsApp app) {
        return opsHostService.requireHost(app.getHostId());
    }

    private static String lock(OpsApp app) {
        return "app:" + app.getId();
    }

    private static Map<String, Object> vars(OpsApp app, Map<String, Object> extra) {
        Map<String, Object> vars = new HashMap<>(extra);
        vars.put("app_name", app.getName());
        vars.put("app_dir", app.getDir());
        return vars;
    }

    private OpsHost fill(OpsApp app, String name, Long hostId, String dir, String remark) {
        String appName = name == null ? "" : name.trim();
        String appDir = normalizeDir(dir);
        String appRemark = remark == null ? "" : remark.trim();
        if (!NAME_PATTERN.matcher(appName).matches()) {
            throw new BusinessException(ErrorCode.OPS_APP_INVALID, "名称只能包含字母、数字、_ . -，最长 64");
        }
        if (appRemark.length() > REMARK_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        OpsHost host = opsHostService.requireHost(hostId);
        app.setName(appName);
        app.setHostId(host.getId());
        app.setDir(appDir);
        app.setRemark(appRemark);
        return host;
    }

    /** 绝对路径，去掉重复和末尾的 /；不允许 . 和 ..、空白和引号 */
    private static String normalizeDir(String dir) {
        String value = dir == null ? "" : dir.trim().replaceAll("/+", "/");
        if (value.length() > 1 && value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (!value.startsWith("/") || value.length() > DIR_MAX_LENGTH || value.equals("/") || !value.matches("[A-Za-z0-9_./-]+")
                || List.of(value.split("/")).stream().anyMatch(part -> part.equals(".") || part.equals(".."))) {
            throw new BusinessException(ErrorCode.OPS_APP_INVALID, "目录必须是绝对路径，只能包含字母、数字、_ . - /，例如 /opt/danmu/log");
        }
        return value;
    }

    private static String requireConfFile(String file) {
        String value = file == null ? "" : file.trim();
        if (!CONF_FILE.matcher(value).matches() || List.of(value.split("/")).stream().anyMatch(part -> part.equals(".") || part.equals(".."))) {
            throw new BusinessException(ErrorCode.OPS_APP_INVALID, "文件名不合法：" + file);
        }
        return value;
    }

    private void requireUnique(OpsApp app) {
        if (opsAppMapper.selectCount(Wrappers.<OpsApp>lambdaQuery().eq(OpsApp::getHostId, app.getHostId()).eq(OpsApp::getDir, app.getDir())
                .ne(app.getId() != null, OpsApp::getId, app.getId())) > 0) {
            throw new BusinessException(ErrorCode.OPS_APP_DIR_EXISTS);
        }
        if (opsAppMapper.selectCount(Wrappers.<OpsApp>lambdaQuery().eq(OpsApp::getHostId, app.getHostId()).eq(OpsApp::getName, app.getName())
                .ne(app.getId() != null, OpsApp::getId, app.getId())) > 0) {
            throw new BusinessException(ErrorCode.OPS_APP_NAME_EXISTS);
        }
    }

    private static OpsApp copy(OpsApp app) {
        var copy = new OpsApp();
        copy.setId(app.getId());
        copy.setName(app.getName());
        copy.setHostId(app.getHostId());
        copy.setDir(app.getDir());
        return copy;
    }

    private static long parseLong(String value) {
        try {
            return value == null || value.isBlank() ? 0 : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0;  // 例如 MemoryCurrent=[not set]
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.debug("删除文件失败，file={}", file, e);
        }
    }
}
