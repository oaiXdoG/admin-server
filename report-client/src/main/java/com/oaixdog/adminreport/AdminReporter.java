package com.oaixdog.adminreport;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 业务服向管理后台定时上报自身信息。只依赖 JDK，任何异常都不会抛到业务线程。
 */
public final class AdminReporter {

    private static final System.Logger LOG = System.getLogger(AdminReporter.class.getName());
    private static final String INSTANCE_ID = resolveInstanceId();
    private static final long PERIOD_SECONDS = 10;
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final long WARN_INTERVAL_MILLIS = 60_000;
    private static final Pattern CODE_PATTERN = Pattern.compile("\"code\"\\s*:\\s*(-?\\d+)");

    private static ScheduledExecutorService executor;

    private AdminReporter() {
    }

    /**
     * 实例 ID：环境变量 ADM_INSTANCE_ID 非空则用它，否则类加载时生成一次 UUID，进程存活期间不变。
     */
    public static String instanceId() {
        return INSTANCE_ID;
    }

    /**
     * 启动上报：立即上报一次，之后每 10 秒一次，每次重新调用 content.get() 取内容。
     * url / secret 优先使用环境变量 ADM_REPORT_URL / ADM_REPORT_SECRET，没有时用参数；重复调用会先停掉旧的。
     */
    public static synchronized void start(String appKey, String type, String nodeId, String url, String secret, Supplier<Map<String, Object>> content) {
        try {
            stop();
            String finalUrl = firstNotBlank(System.getenv("ADM_REPORT_URL"), url);
            String finalSecret = firstNotBlank(System.getenv("ADM_REPORT_SECRET"), secret);
            if (isBlank(appKey) || finalUrl == null || finalSecret == null) {
                LOG.log(System.Logger.Level.INFO, "未配置管理后台上报地址或密钥，不上报");
                return;
            }
            Task task = new Task(appKey.trim(), type, nodeId, finalUrl, finalSecret, content);
            executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "admin-reporter");
                thread.setDaemon(true);
                return thread;
            });
            executor.scheduleAtFixedRate(task, 0, PERIOD_SECONDS, TimeUnit.SECONDS);
            LOG.log(System.Logger.Level.INFO, "管理后台上报已启动，instanceId={0}，地址={1}", INSTANCE_ID, finalUrl);
        } catch (Throwable e) {
            LOG.log(System.Logger.Level.WARNING, "启动管理后台上报失败", e);
        }
    }

    /**
     * 停止上报。
     */
    public static synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private static String resolveInstanceId() {
        String env = System.getenv("ADM_INSTANCE_ID");
        return isBlank(env) ? UUID.randomUUID().toString() : env.trim();
    }

    private static String firstNotBlank(String first, String second) {
        if (!isBlank(first)) {
            return first.trim();
        }
        return isBlank(second) ? null : second.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 一次上报配置；每次 start 新建，避免并发修改。
     */
    private static final class Task implements Runnable {

        private final String appKey;
        private final String type;
        private final String nodeId;
        private final URI uri;
        private final String secret;
        private final Supplier<Map<String, Object>> content;
        private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        private final Map<String, Long> lastWarnAt = new ConcurrentHashMap<>();
        private boolean succeeded;

        private Task(String appKey, String type, String nodeId, String url, String secret, Supplier<Map<String, Object>> content) {
            this.appKey = appKey;
            this.type = type;
            this.nodeId = nodeId;
            this.uri = URI.create(url);
            this.secret = secret;
            this.content = content;
        }

        @Override
        public void run() {
            try {
                report();
            } catch (Throwable e) {
                warn(e.getClass().getName(), "管理后台上报异常：" + e);
            }
        }

        private void report() throws Exception {
            String body = toJson(buildBody());
            String timestamp = String.valueOf(System.currentTimeMillis());
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("X-Admin-Project", appKey)
                    .header("X-Admin-Timestamp", timestamp)
                    .header("X-Admin-Signature", sign(secret, timestamp, body))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                warn("HTTP " + response.statusCode(), "管理后台上报失败：HTTP " + response.statusCode());
                return;
            }
            String responseBody = response.body() == null ? "" : response.body();
            Matcher matcher = CODE_PATTERN.matcher(responseBody);
            if (!matcher.find()) {
                warn("invalid response", "管理后台上报失败：响应格式不正确 " + abbreviate(responseBody));
                return;
            }
            String code = matcher.group(1);
            if (!"0".equals(code)) {
                warn("code " + code, "管理后台上报失败：" + abbreviate(responseBody));
                return;
            }
            if (!succeeded) {
                succeeded = true;
                LOG.log(System.Logger.Level.INFO, "管理后台上报成功，instanceId={0}", INSTANCE_ID);
            }
        }

        private Map<String, Object> buildBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            Map<String, Object> values = content == null ? null : content.get();
            if (values != null) {
                body.putAll(values);
            }
            body.put("type", type);
            if (isBlank(nodeId)) {
                body.remove("nodeId");
            } else {
                body.put("nodeId", nodeId);
            }
            body.put("instanceId", INSTANCE_ID);
            return body;
        }

        /**
         * 同一类原因每分钟最多打印一次。
         */
        private void warn(String reason, String message) {
            long now = System.currentTimeMillis();
            Long last = lastWarnAt.get(reason);
            if (last != null && now - last < WARN_INTERVAL_MILLIS) {
                return;
            }
            lastWarnAt.put(reason, now);
            LOG.log(System.Logger.Level.WARNING, message);
        }
    }

    /**
     * hex(HMAC-SHA256(secret, 时间戳 + "\n" + hex(SHA-256(请求体字节))))，十六进制小写。
     */
    static String sign(String secret, String timestamp, String body) throws Exception {
        HexFormat hex = HexFormat.of();
        String bodyHash = hex.formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return hex.formatHex(mac.doFinal((timestamp + "\n" + bodyHash).getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * 只支持扁平 Map，值为 String / Number / Boolean / null；其他类型按 toString 作为字符串。
     */
    static String toJson(Map<String, Object> map) {
        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            if (!first) {
                builder.append(',');
            }
            first = false;
            appendString(builder, entry.getKey());
            builder.append(':');
            appendValue(builder, entry.getValue());
        }
        return builder.append('}').toString();
    }

    private static void appendValue(StringBuilder builder, Object value) {
        if (value == null) {
            builder.append("null");
        } else if (value instanceof Boolean) {
            builder.append(value);
        } else if (value instanceof Double number && (number.isNaN() || number.isInfinite())) {
            builder.append("null");
        } else if (value instanceof Float number && (number.isNaN() || number.isInfinite())) {
            builder.append("null");
        } else if (value instanceof Number) {
            builder.append(value);
        } else {
            appendString(builder, value.toString());
        }
    }

    private static void appendString(StringBuilder builder, String value) {
        builder.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        builder.append(String.format("\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                }
            }
        }
        builder.append('"');
    }

    private static String abbreviate(String value) {
        return value.length() > 200 ? value.substring(0, 200) + "..." : value;
    }
}
