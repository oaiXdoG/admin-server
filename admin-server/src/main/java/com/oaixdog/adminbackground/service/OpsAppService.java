package com.oaixdog.adminbackground.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.oaixdog.adminbackground.entity.Account;
import com.oaixdog.adminbackground.entity.OpsApp;
import com.oaixdog.adminbackground.exception.BusinessException;
import com.oaixdog.adminbackground.exception.ErrorCode;
import com.oaixdog.adminbackground.mapper.OpsAppMapper;
import com.oaixdog.adminbackground.protocol.OpsAppResponse;
import com.oaixdog.adminbackground.protocol.OpsAppState;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.oaixdog.adminbackground.service.SshRunner.quote;

/**
 * 程序：保存在 ops_app，每条是一台机器（IP、SSH 端口）上的一个目录（自己配置，例如 /usr/local/games/game1）。
 * 新增，或修改了 IP、端口时，先 SSH 连一次，连得上才保存；运行中的程序要先关闭才能删除，删除只删记录、不动服务器。
 * <p>
 * 程序包和程序目录的格式都是 bin、lib、conf、excel，bin/app.sh 随包发布。启停、发布都是通过 SSH 执行 bash &lt;目录&gt;/bin/app.sh &lt;命令&gt;，
 * 在服务器上手动执行效果一样；发布时由新包里的 bin/app.sh 把自己发布到程序目录。启停、发布在后台执行（{@link OpsTaskService}），立即返回任务 id。
 * 列表里的运行状态、启动时间来自 bin/app.sh status。
 * <p>
 * 查看列表需要 ops_app；新建、编辑、删除需要 ops_app:manage；启停 ops_app:control；发布 ops_app:deploy。
 */
@Service
@RequiredArgsConstructor
public class OpsAppService {

    private static final Logger log = LoggerFactory.getLogger(OpsAppService.class);

    private static final String MENU = "ops_app";
    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final int DIR_MAX_LENGTH = 256;
    private static final int REMARK_MAX_LENGTH = 128;
    private static final Set<String> ACTIONS = Set.of("start", "stop", "restart");
    /** 前端每片 2MB，base64 后约 2.7MB，在 HTTP 请求体上限 16MB 以内 */
    private static final int CHUNK_MAX_SIZE = 4 * 1024 * 1024;
    private static final long PACKAGE_MAX_SIZE = 1024L * 1024 * 1024;
    private static final long UPLOAD_EXPIRE_MILLIS = 3_600_000;
    /** 程序包暂存目录：管理后台目录（bin/app.sh 启动时的工作目录）下的 upload/ */
    private static final Path UPLOAD_DIR = Path.of("upload");
    private static final long READ_TIMEOUT_SECONDS = 30;
    private static final long SSH_CHECK_TIMEOUT_SECONDS = 20;
    /** IPv4 或主机名 */
    private static final Pattern ADDRESS = Pattern.compile("[A-Za-z0-9]([A-Za-z0-9.-]{0,62}[A-Za-z0-9])?");
    private static final long CONTROL_TIMEOUT_SECONDS = 180;
    private static final long DEPLOY_TIMEOUT_SECONDS = 900;

    private final PermissionService permissionService;
    private final SshRunner sshRunner;
    private final OpsTaskService opsTaskService;
    private final OpsAppMapper opsAppMapper;

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

    /**
     * ip 为空时返回全部程序；每台机器（IP、端口）一次 SSH 读出它上面所有程序的状态，各台并行。
     * ips 为所有程序用到的 IP，给页面的筛选下拉框。
     */
    public Map<String, Object> list(String authorization, String ip) {
        permissionService.requireMenu(authorization, MENU);
        String filter = ip == null ? "" : ip.trim();
        List<OpsApp> all = opsAppMapper.selectList(Wrappers.<OpsApp>lambdaQuery()
                .orderByAsc(OpsApp::getIp).orderByAsc(OpsApp::getName).orderByAsc(OpsApp::getId));
        List<String> ips = all.stream().map(OpsApp::getIp).distinct().toList();
        List<OpsApp> apps = filter.isEmpty() ? all : all.stream().filter(app -> app.getIp().equals(filter)).toList();
        Map<SshRunner.Target, List<OpsApp>> appsByTarget = apps.stream()
                .collect(Collectors.groupingBy(OpsAppService::target, LinkedHashMap::new, Collectors.toList()));

        Map<SshRunner.Target, CompletableFuture<Map<Long, OpsAppState>>> statuses = new HashMap<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            appsByTarget.forEach((target, targetApps) ->
                    statuses.put(target, CompletableFuture.supplyAsync(() -> status(target, targetApps), executor)));
        }

        List<OpsAppResponse> list = new ArrayList<>();
        for (OpsApp app : apps) {
            OpsAppResponse response = OpsAppResponse.valueOf(app);
            response.setBusy(opsTaskService.isBusy(lock(app)));
            try {
                response.setState(statuses.get(target(app)).join().get(app.getId()));
                response.setHostOnline(true);
            } catch (RuntimeException exception) {
                Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                response.setError(cause.getMessage());
            }
            list.add(response);
        }
        return Map.of("list", list, "ips", ips);
    }

    public OpsAppResponse create(String authorization, String name, String ip, Integer sshPort, String dir, String remark) {
        permissionService.requireMenu(authorization, MENU + ":manage");
        var app = new OpsApp();
        fill(app, name, ip, sshPort, dir, remark);
        requireUnique(app);
        checkSsh(app);
        long now = System.currentTimeMillis();
        app.setDeployedAt(0L);
        app.setCreatedAt(now);
        app.setUpdatedAt(now);
        try {
            opsAppMapper.insert(app);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.OPS_APP_NAME_EXISTS);
        }
        return OpsAppResponse.valueOf(app);
    }

    /** 只改记录，不动服务器；有操作在执行时不能改 */
    public void update(String authorization, Long appId, String name, String ip, Integer sshPort, String dir, String remark) {
        permissionService.requireMenu(authorization, MENU + ":manage");
        OpsApp app = requireApp(appId);
        requireIdle(app);
        SshRunner.Target old = target(app);
        fill(app, name, ip, sshPort, dir, remark);
        requireUnique(app);
        if (!old.equals(target(app))) {
            checkSsh(app);
        }
        app.setUpdatedAt(System.currentTimeMillis());
        try {
            opsAppMapper.updateById(app);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.OPS_APP_NAME_EXISTS);
        }
    }

    /** 只删记录，不停止进程、不删服务器上的文件；先 SSH 确认程序没有在运行，连不上时不能删除 */
    public void remove(String authorization, Long appId) {
        permissionService.requireMenu(authorization, MENU + ":manage");
        OpsApp app = requireApp(appId);
        requireIdle(app);
        OpsAppState state;
        try {
            state = status(target(app), List.of(app)).get(app.getId());
        } catch (BusinessException exception) {
            throw new BusinessException(ErrorCode.OPS_SSH_FAILED, "无法确认程序是否已关闭：" + exception.getMessage());
        }
        if (state != null && state.isRunning()) {
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
        SshRunner.Target host = target(app);
        String verb = Map.of("start", "启动", "stop", "停止", "restart", "重启").get(action);
        return opsTaskService.submit(account, action, verb + " " + app.getName() + "（" + app.getIp() + "）", app.getId(),
                lock(app), out -> runScript(host, app, action, null, out, CONTROL_TIMEOUT_SECONDS));
    }

    public long deploy(String authorization, Long appId, String uploadId, Boolean overwriteConf) {
        Account account = permissionService.requireMenu(authorization, MENU + ":deploy");
        OpsApp app = requireApp(appId);
        SshRunner.Target host = target(app);
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
                + (Boolean.TRUE.equals(overwriteConf) ? " --overwrite-conf" : "") + "; fi"
                + "; rc=$?; rm -rf " + remoteTmp + " " + remoteZip + "; exit $rc";
        try {
            return opsTaskService.submit(account, "deploy", "发布 " + app.getName() + "（" + app.getIp() + "）", app.getId(),
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

    // ---------------------------------------------------------------- 上传

    /** 程序包先分片上传到管理后台目录下的 upload/，发布时再通过 SSH 传到服务器；1 小时没有新分片的上传被删除 */
    public Map<String, Object> uploadStart(String authorization, Long appId) {
        permissionService.requireMenu(authorization, MENU + ":deploy");
        OpsApp app = requireApp(appId);
        cleanExpiredUploads();
        Path dir = UPLOAD_DIR;
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
        Path dir = UPLOAD_DIR;
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

    /** 一次 SSH 读出这台机器上这些程序的状态（逐个执行 bin/app.sh status），程序 id → 状态 */
    private Map<Long, OpsAppState> status(SshRunner.Target host, List<OpsApp> apps) {
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
            }
            states.put(app.getId(), state);
        }
        return states;
    }

    // ---------------------------------------------------------------- 工具

    private OpsApp requireApp(Long appId) {
        OpsApp app = appId == null ? null : opsAppMapper.selectById(appId);
        if (app == null) {
            throw new BusinessException(ErrorCode.OPS_APP_NOT_FOUND);
        }
        return app;
    }

    private static String lock(OpsApp app) {
        return "app:" + app.getId();
    }

    /** 程序目录里的 bin/app.sh，已加引号 */
    private static String script(OpsApp app) {
        return quote(app.getDir() + "/bin/app.sh");
    }

    /** 执行 bin/app.sh 的一个子命令（args 已加引号），输出交给 out，返回是否成功 */
    private boolean runScript(SshRunner.Target host, OpsApp app, String args, SshRunner.Input input, Consumer<String> out,
                              long timeoutSeconds) {
        String command = "if [ ! -f " + script(app) + " ]; then echo " + quote("还没有发布：" + app.getDir() + "/bin/app.sh 不存在")
                + "; exit 1; fi; bash " + script(app) + " " + args;
        return sshRunner.run(host, command, input, out, timeoutSeconds) == 0;
    }

    private void fill(OpsApp app, String name, String ip, Integer sshPort, String dir, String remark) {
        String appName = name == null ? "" : name.trim();
        String address = ip == null ? "" : ip.trim();
        int port = sshPort == null ? 22 : sshPort;
        String appDir = normalizeDir(dir);
        String appRemark = remark == null ? "" : remark.trim();
        if (!NAME_PATTERN.matcher(appName).matches()) {
            throw new BusinessException(ErrorCode.OPS_APP_INVALID, "名称只能包含字母、数字、_ . -，最长 64");
        }
        if (!ADDRESS.matcher(address).matches()) {
            throw new BusinessException(ErrorCode.OPS_APP_INVALID, "IP 不合法");
        }
        if (port < 1 || port > 65535) {
            throw new BusinessException(ErrorCode.OPS_APP_INVALID, "SSH 端口不合法");
        }
        if (appRemark.length() > REMARK_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        app.setName(appName);
        app.setIp(address);
        app.setSshPort(port);
        app.setDir(appDir);
        app.setRemark(appRemark);
    }

    /** 连不上时抛出 OPS_SSH_FAILED，message 为 ssh 的输出 */
    private void checkSsh(OpsApp app) {
        sshRunner.exec(target(app), "true", SSH_CHECK_TIMEOUT_SECONDS);
    }

    private void requireIdle(OpsApp app) {
        if (opsTaskService.isBusy(lock(app))) {
            throw new BusinessException(ErrorCode.OPS_TASK_BUSY);
        }
    }

    private static SshRunner.Target target(OpsApp app) {
        return new SshRunner.Target(app.getIp(), app.getSshPort() == null ? 22 : app.getSshPort());
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

    private void requireUnique(OpsApp app) {
        if (opsAppMapper.selectCount(Wrappers.<OpsApp>lambdaQuery().eq(OpsApp::getIp, app.getIp()).eq(OpsApp::getDir, app.getDir())
                .ne(app.getId() != null, OpsApp::getId, app.getId())) > 0) {
            throw new BusinessException(ErrorCode.OPS_APP_DIR_EXISTS);
        }
        if (opsAppMapper.selectCount(Wrappers.<OpsApp>lambdaQuery().eq(OpsApp::getIp, app.getIp()).eq(OpsApp::getName, app.getName())
                .ne(app.getId() != null, OpsApp::getId, app.getId())) > 0) {
            throw new BusinessException(ErrorCode.OPS_APP_NAME_EXISTS);
        }
    }

    private static long parseLong(String value) {
        try {
            return value == null || value.isBlank() ? 0 : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0;
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
