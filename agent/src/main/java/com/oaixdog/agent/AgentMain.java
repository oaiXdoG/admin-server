package com.oaixdog.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 服务器管理 Agent：首次启动用安装令牌注册，之后每 5 秒向后台发心跳（上报主机和托管进程的指标，领取指令），
 * 指令在单独的线程里按顺序执行，执行完回传结果。用法：java -jar admin-agent.jar /opt/admin-agent/agent.properties
 */
public final class AgentMain {

    static final String VERSION = "0.4.0";
    private static final int HEARTBEAT_SECONDS = 5;
    /** 长轮询：后台最长挂起 25 秒，请求超时要比它长；失败后隔几秒再试 */
    private static final int WAIT_TIMEOUT_SECONDS = 40;
    private static final int WAIT_RETRY_SECONDS = 5;
    /** 注册失败（令牌无效）时退出码，安装脚本生成的服务配置了不自动重启 */
    private static final int EXIT_REGISTER_FAILED = 2;

    private final Path configFile;
    private final Path stateFile;
    private final Properties config = new Properties();
    /** 进程 ID → 最近一次启动成功时的配置版本 */
    private final Properties state = new Properties();
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Metrics metrics = new Metrics();
    private final Map<Long, Definition> definitions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    /** 长轮询线程：等待期间不占 CPU，后台有指令时立即返回 */
    private final ScheduledExecutorService waiter = Executors.newSingleThreadScheduledExecutor();
    /** 不同进程的指令并行执行（启动最多要等 180 秒，不能挡住其他进程）；同一进程同时只有一条指令，由后台保证 */
    private final ExecutorService taskExecutor = Executors.newCachedThreadPool();
    /** 文件操作不排在指令后面（部署可能要几分钟） */
    private final ExecutorService fileExecutor = Executors.newSingleThreadExecutor();

    record Definition(long processId, String name, int configVersion, int port, String workDir, String runUser, String unit) {
    }

    private AgentMain(Path configFile) {
        this.configFile = configFile;
        this.stateFile = configFile.resolveSibling("state.properties");
    }

    public static void main(String[] args) throws IOException {
        Path configFile = Path.of(args.length > 0 ? args[0] : "/opt/admin-agent/agent.properties");
        new AgentMain(configFile).start();
    }

    private void start() throws IOException {
        load(configFile, config);
        if (Files.exists(stateFile)) {
            load(stateFile, state);
        }
        if (blank(config.getProperty("serverId"))) {
            register();
        }
        log("admin-agent " + VERSION + " 已启动，服务器 ID " + config.getProperty("serverId") + "，后台 " + baseUrl());
        // 先采一次作为基准，CPU、网络速率按两次采集的差值计算，第一次心跳就有真实数值
        metrics.host();
        scheduler.scheduleWithFixedDelay(this::heartbeat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
        waiter.execute(this::waitCommands);
    }

    /** 用安装令牌换取服务器 ID 和密钥，写回配置文件并删除令牌 */
    private void register() throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", config.getProperty("token", ""));
        body.put("hostname", metrics.hostname());
        body.put("os", metrics.osName());
        body.put("privateIp", metrics.privateIps());
        body.put("cpuCores", metrics.cpuCores());
        body.put("memoryTotal", metrics.memoryTotal());
        body.put("agentVersion", VERSION);
        try {
            JsonNode data = post("/api/agent/register", body, false);
            config.setProperty("serverId", data.path("serverId").asText());
            config.setProperty("secret", data.path("secret").asText());
            config.remove("token");
            save(configFile, config);
            log("注册成功");
        } catch (IOException | InterruptedException exception) {
            log("注册失败：" + exception.getMessage());
            System.exit(EXIT_REGISTER_FAILED);
        }
    }

    private void heartbeat() {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("hostname", metrics.hostname());
            body.put("os", metrics.osName());
            body.put("privateIp", metrics.privateIps());
            body.put("cpuCores", metrics.cpuCores());
            body.put("memoryTotal", metrics.memoryTotal());
            body.put("agentVersion", VERSION);
            Map<Integer, Integer> tcp = metrics.tcpConnections();
            Map<String, Object> host = metrics.host();
            host.put("tcp", tcp.getOrDefault(-1, 0));
            body.put("host", host);
            body.put("processes", processMetrics(tcp));
            body.put("longPoll", true);
            JsonNode data = post("/api/agent/heartbeat", body, true);
            syncDefinitions(data.path("processes"));
            // 后台不支持长轮询时（旧版本）指令仍随心跳下发
            handleCommands(data);
        } catch (Exception exception) {
            log("心跳失败：" + exception.getMessage());
        }
    }

    /** 长轮询领取指令：返回后立即发起下一次；失败（后台重启、网络断开）隔几秒再试 */
    private void waitCommands() {
        try {
            handleCommands(post("/api/agent/wait", Map.of("agentVersion", VERSION), true, WAIT_TIMEOUT_SECONDS));
            waiter.execute(this::waitCommands);
        } catch (Exception exception) {
            log("等待指令失败：" + exception.getMessage());
            waiter.schedule(this::waitCommands, WAIT_RETRY_SECONDS, TimeUnit.SECONDS);
        }
    }

    private void handleCommands(JsonNode data) {
        for (JsonNode task : data.path("tasks")) {
            long taskId = task.path("taskId").asLong();
            long processId = task.path("processId").asLong();
            String processName = task.path("processName").asText();
            String action = task.path("action").asText();
            JsonNode param = task.path("param");
            taskExecutor.submit(() -> execute(taskId, processId, processName, action, param));
        }
        for (JsonNode op : data.path("fileOps")) {
            fileExecutor.submit(() -> fileOp(op));
        }
    }

    private List<Map<String, Object>> processMetrics(Map<Integer, Integer> tcp) {
        List<Map<String, Object>> result = new ArrayList<>();
        List<Integer> pids = new ArrayList<>();
        for (Definition definition : definitions.values()) {
            Map<String, String> show = Systemd.show(definition.name());
            int pid = parseInt(show.get("MainPID"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("processId", definition.processId());
            item.put("state", show.getOrDefault("ActiveState", "unknown"));
            item.put("pid", pid);
            Map<String, Object> process = metrics.process(pid);
            if (process != null) {
                item.putAll(process);
                item.put("tcp", tcp.getOrDefault(pid, 0));
                pids.add(pid);
            }
            item.put("configVersion", parseInt(state.getProperty(String.valueOf(definition.processId()))));
            result.add(item);
        }
        metrics.retainProcesses(pids);
        return result;
    }

    /**
     * 服务文件、环境变量文件内容与后台不同时写入并 daemon-reload；后台已删除的进程只在收到 remove 指令时才删除这两个文件。
     * 环境变量文件为后台下发的 env（ADM_INSTANCE_ID、ADM_REPORT_SECRET）加上 ADM_REPORT_URL（本 Agent 的后台地址，Agent 能连上就一定可达）
     */
    private void syncDefinitions(JsonNode processes) throws IOException {
        Map<Long, Definition> latest = new HashMap<>();
        boolean changed = false;
        for (JsonNode node : processes) {
            Definition definition = new Definition(node.path("processId").asLong(), node.path("name").asText(), node.path("configVersion").asInt(),
                    node.path("port").asInt(), node.path("workDir").asText(), node.path("runUser").asText(), node.path("unit").asText());
            changed |= Systemd.writeUnit(definition.name(), definition.unit());
            if (node.path("env").isObject()) {
                Map<String, String> env = new LinkedHashMap<>();
                node.path("env").fields().forEachRemaining(entry -> env.put(entry.getKey(), entry.getValue().asText()));
                env.put("ADM_REPORT_URL", baseUrl() + "/api/node/report");
                changed |= Systemd.writeEnv(definition.name(), env);
            }
            latest.put(definition.processId(), definition);
        }
        if (changed) {
            Systemd.daemonReload();
        }
        definitions.keySet().retainAll(latest.keySet());
        definitions.putAll(latest);
    }

    private void execute(long taskId, long processId, String processName, String action, JsonNode param) {
        Systemd.Result result;
        try {
            Definition definition = definitions.get(processId);
            if ("remove".equals(action)) {
                result = Systemd.remove(processName);
                saveState(processId, null);
            } else if (definition == null) {
                result = new Systemd.Result(false, "本机没有这个进程的定义，请稍后重试\n");
            } else if ("stop".equals(action)) {
                result = Systemd.stop(definition.name());
            } else if ("deploy".equals(action)) {
                String output = Deployer.deploy(index -> Base64.getDecoder().decode(post("/api/agent/package", Map.of("taskId", taskId, "index", index), true).path("data").asText()),
                        param.path("chunks").asInt(), param.path("size").asLong(), param.path("sha256").asText(), Path.of(definition.workDir()), definition.runUser());
                Systemd.Result restart = Systemd.start(definition.name(), "restart", definition.port());
                result = new Systemd.Result(restart.success(), output + restart.output());
                if (restart.success()) {
                    saveState(processId, definition.configVersion());
                }
            } else if ("start".equals(action) || "restart".equals(action)) {
                result = Systemd.start(definition.name(), action, definition.port());
                if (result.success()) {
                    saveState(processId, definition.configVersion());
                }
            } else {
                result = new Systemd.Result(false, "不支持的指令：" + action + "\n");
            }
        } catch (Exception exception) {
            result = new Systemd.Result(false, "执行失败：" + exception.getMessage() + "\n");
        }
        log("指令 " + taskId + " " + action + " " + processName + (result.success() ? " 成功" : " 失败"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskId", taskId);
        body.put("success", result.success());
        body.put("output", result.output());
        try {
            post("/api/agent/result", body, true);
        } catch (IOException | InterruptedException exception) {
            log("回传指令 " + taskId + " 结果失败：" + exception.getMessage());
        }
        // 立即上报一次进程状态，页面不用等下一次心跳
        scheduler.execute(this::heartbeat);
    }

    /** 记录进程启动成功时的配置版本（null 为删除）；指令可能并行执行，写文件要串行 */
    private void saveState(long processId, Integer configVersion) throws IOException {
        synchronized (state) {
            if (configVersion == null) {
                state.remove(String.valueOf(processId));
            } else {
                state.setProperty(String.valueOf(processId), String.valueOf(configVersion));
            }
            save(stateFile, state);
        }
    }

    /** 文件操作：在进程工作目录下执行，结果（data 为 JSON 文本）回传后台 */
    private void fileOp(JsonNode op) {
        String opId = op.path("opId").asText();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("opId", opId);
        try {
            Definition definition = definitions.get(op.path("processId").asLong());
            if (definition == null) {
                throw new IOException("本机没有这个进程的定义，请稍后重试");
            }
            Object data = FileOps.run(op.path("op").asText(), Path.of(definition.workDir()), op.path("path").asText(), op.path("content").asText());
            body.put("success", true);
            body.put("message", "");
            body.put("data", data == null ? "" : json.writeValueAsString(data));
        } catch (Exception exception) {
            body.put("success", false);
            body.put("message", exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
            body.put("data", "");
        }
        try {
            post("/api/agent/file-result", body, true);
        } catch (IOException | InterruptedException exception) {
            log("回传文件操作 " + opId + " 结果失败：" + exception.getMessage());
        }
    }

    private JsonNode post(String path, Object body, boolean signed) throws IOException, InterruptedException {
        return post(path, body, signed, 20);
    }

    private JsonNode post(String path, Object body, boolean signed, int timeoutSeconds) throws IOException, InterruptedException {
        String text = json.writeValueAsString(body);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl() + path)).timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(text, StandardCharsets.UTF_8));
        if (signed) {
            String timestamp = String.valueOf(System.currentTimeMillis());
            builder.header("X-Agent-Server", config.getProperty("serverId")).header("X-Agent-Timestamp", timestamp)
                    .header("X-Agent-Signature", sign(config.getProperty("secret"), timestamp + "\n" + sha256(text)));
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode node = response.statusCode() == 200 ? json.readTree(response.body()) : null;
        if (node == null || node.path("code").asInt(-1) != 0) {
            String message = node == null ? "HTTP " + response.statusCode() : node.path("message").asText();
            throw new IOException(path + " " + message);
        }
        return node.path("data");
    }

    private String baseUrl() {
        return config.getProperty("baseUrl", "").replaceAll("/+$", "");
    }

    private static String sign(String secret, String content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void load(Path file, Properties properties) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
    }

    /** 配置里有密钥，只允许 root 读写 */
    private static void save(Path file, Properties properties) throws IOException {
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            properties.store(writer, "admin-agent");
        }
        Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
        Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    private static int parseInt(String value) {
        try {
            return value == null ? 0 : Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static void log(String message) {
        System.out.println(message);
    }
}
