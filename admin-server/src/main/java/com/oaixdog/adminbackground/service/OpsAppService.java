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
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.oaixdog.adminbackground.service.SshRunner.quote;

/**
 * 程序：保存在 ops_app，每条绑定一台主机和它上面的一个目录（自己配置，例如 /usr/local/games/game1）。
 * <p>
 * 程序包和程序目录的格式都是 bin、lib、conf、excel，bin/app.sh 随包发布。所有操作都是通过 SSH 执行 bash &lt;目录&gt;/bin/app.sh &lt;命令&gt;，
 * 在服务器上手动执行效果一样；发布时由新包里的 bin/app.sh 把自己发布到程序目录。启停、发布、回滚、保存配置在后台执行（{@link OpsTaskService}），立即返回任务 id；
 * 状态、日志、配置文件的读取直接执行。
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
    private static final List<String> LOG_FILES = List.of("server.log", "error.log", "console.log");
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
    private final SshRunner sshRunner;
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

    /** 只保存登记，不连接服务器；目录在第一次发布时创建 */
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

    /** 修改主机、目录时程序必须已停止；服务器上的目录不动 */
    public OpsAppResponse update(String authorization, Long appId, String name, Long hostId, String dir, String remark) {
        permissionService.requireMenu(authorization, MENU + ":manage");
        OpsApp app = requireApp(appId);
        requireIdle(app);
        OpsApp old = copy(app);
        OpsHost host = fill(app, name, hostId, dir, remark);
        boolean moved = !old.getHostId().equals(app.getHostId()) || !old.getDir().equals(app.getDir());
        if (moved && isRunning(old)) {
            throw new BusinessException(ErrorCode.OPS_APP_RUNNING);
        }
        app.setUpdatedAt(System.currentTimeMillis());
        requireUnique(app);
        try {
            opsAppMapper.updateById(app);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.OPS_APP_NAME_EXISTS);
        }
        return OpsAppResponse.valueOf(app, host);
    }

    /** 程序必须已停止；只删除登记，不删除服务器上的程序目录 */
    public void remove(String authorization, Long appId) {
        permissionService.requireMenu(authorization, MENU + ":manage");
        OpsApp app = requireApp(appId);
        requireIdle(app);
        if (isRunning(app)) {
            throw new BusinessException(ErrorCode.OPS_APP_RUNNING);
        }
        opsAppMapper.deleteById(app.getId());
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
                lock(app), out -> runScript(host, app, action, null, out, CONTROL_TIMEOUT_SECONDS));
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
        String remoteZip = quote("/tmp/app-deploy-" + uploadId + ".zip");
        String remoteTmp = quote("/tmp/app-deploy-" + uploadId);
        // 解压到临时目录，执行新包里的 bin/app.sh deploy <程序目录>（zip 里外面可以再包一层目录）
        String command = "rm -rf " + remoteTmp + " && mkdir -p " + remoteTmp + " && unzip -q " + remoteZip + " -d " + remoteTmp
                + " && s=$(ls " + remoteTmp + "/bin/app.sh " + remoteTmp + "/*/bin/app.sh 2>/dev/null | head -1)"
                + " && if [ -z \"$s\" ]; then echo '程序包里没有 bin/app.sh'; false; else bash \"$s\" deploy " + quote(app.getDir())
                + " --keep " + properties.getOpsBackupKeep() + (Boolean.TRUE.equals(overwriteConf) ? " --overwrite-conf" : "") + "; fi"
                + "; rc=$?; rm -rf " + remoteTmp + " " + remoteZip + "; exit $rc";
        try {
            return opsTaskService.submit(account, "deploy", "发布 " + app.getName() + "（" + host.getIp() + "）", host.getId(), app.getId(),
                    lock(app), out -> {
                        try {
                            out.accept("== 上传程序包，" + upload.size + " 字节");
                            sshRunner.exec(host, "cat > " + remoteZip, SshRunner.Input.of(upload.file), DEPLOY_TIMEOUT_SECONDS);
                            boolean success = sshRunner.run(host, command, null, out, DEPLOY_TIMEOUT_SECONDS) == 0;
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
        String output = sshRunner.exec(host(app), "bash " + script(app) + " backups", READ_TIMEOUT_SECONDS);
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
                host.getId(), app.getId(), lock(app), out -> runScript(host, app, "rollback " + backup, null, out, DEPLOY_TIMEOUT_SECONDS));
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
        String content = sshRunner.exec(host(app), "bash " + script(app) + " log " + name + " " + count, READ_TIMEOUT_SECONDS);
        return Map.of("content", content);
    }

    public Map<String, Object> configList(String authorization, Long appId) {
        permissionService.requireMenu(authorization, MENU + ":config");
        OpsApp app = requireApp(appId);
        String output = sshRunner.exec(host(app), "bash " + script(app) + " conf-list", READ_TIMEOUT_SECONDS);
        List<String> files = Arrays.stream(output.split("\n")).map(String::trim).filter(it -> !it.isEmpty()).toList();
        return Map.of("files", files);
    }

    public Map<String, Object> configGet(String authorization, Long appId, String file) {
        permissionService.requireMenu(authorization, MENU + ":config");
        OpsApp app = requireApp(appId);
        String content = sshRunner.exec(host(app), "bash " + script(app) + " conf-get " + quote(requireConfFile(file)), READ_TIMEOUT_SECONDS);
        return Map.of("content", content);
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
        String title = "保存 " + app.getName() + " 的 conf/" + name + (restartAfter ? " 并重启" : "") + "（" + host.getIp() + "）";
        return opsTaskService.submit(account, "config", title, host.getId(), app.getId(), lock(app), out -> {
            if (!runScript(host, app, "conf-put " + quote(name), SshRunner.Input.of(bytes), out, CONTROL_TIMEOUT_SECONDS)) {
                return false;
            }
            return !restartAfter || runScript(host, app, "restart", null, out, CONTROL_TIMEOUT_SECONDS);
        });
    }

    // ---------------------------------------------------------------- 上传

    /** 程序包先分片上传到管理后台本机的 ops.upload.dir，发布时再通过 SSH 传到服务器；1 小时没有新分片的上传被删除 */
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

    /** 一次 SSH 读出这台主机上这些程序的状态（逐个执行 bin/app.sh status），程序 id → 状态 */
    private Map<Long, OpsAppState> status(OpsHost host, List<OpsApp> apps) {
        StringBuilder command = new StringBuilder();
        for (OpsApp app : apps) {
            command.append("echo @app=").append(app.getId()).append("; ")
                    .append("if [ -f ").append(script(app)).append(" ]; then bash ").append(script(app)).append(" status; ")
                    .append("else echo deployed=0; fi; ");
        }
        String output = sshRunner.exec(host, command.toString(), READ_TIMEOUT_SECONDS);
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
            state.setDeployed("1".equals(v.get("deployed")));
            state.setRunning("1".equals(v.get("running")));
            if (state.isRunning()) {
                state.setStatus(OpsAppState.RUNNING);
            } else {
                state.setStatus(state.isDeployed() ? OpsAppState.STOPPED : OpsAppState.NOT_DEPLOYED);
            }
            if (state.isRunning()) {
                long pid = parseLong(v.get("pid"));
                state.setPid(pid);
                state.setStartTime(now - parseLong(v.get("elapsed")) * 1000);
                state.setMemory(parseLong(v.get("rss")) * 1024);
                long cpu = parseLong(v.get("cpu_ms"));
                long[] last = lastCpu.put(app.getId(), new long[]{cpu, now, pid});
                if (last != null && last[2] == pid && now > last[1] && cpu >= last[0]) {
                    state.setCpuUsage((double) (cpu - last[0]) / (now - last[1]) / cores);
                }
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

    /** 程序目录里的 bin/app.sh，已加引号 */
    private static String script(OpsApp app) {
        return quote(app.getDir() + "/bin/app.sh");
    }

    /** 执行 bin/app.sh 的一个子命令（args 已加引号），输出交给 out，返回是否成功 */
    private boolean runScript(OpsHost host, OpsApp app, String args, SshRunner.Input input, Consumer<String> out,
                              long timeoutSeconds) {
        String command = "if [ ! -f " + script(app) + " ]; then echo " + quote("还没有发布：" + app.getDir() + "/bin/app.sh 不存在")
                + "; exit 1; fi; bash " + script(app) + " " + args;
        return sshRunner.run(host, command, input, out, timeoutSeconds) == 0;
    }

    /** 有任务在执行时不能修改、删除登记 */
    private void requireIdle(OpsApp app) {
        if (opsTaskService.isBusy(lock(app))) {
            throw new BusinessException(ErrorCode.OPS_TASK_BUSY);
        }
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
