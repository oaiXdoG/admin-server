package com.oaixdog.adminbackground.service;

import com.oaixdog.adminbackground.exception.BusinessException;
import com.oaixdog.adminbackground.exception.ErrorCode;
import com.zfoo.protocol.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;

/**
 * 调用业务服务器的查询接口（接口规范见 docs/data-center.md）：POST JSON，请求头带时间戳和 HMAC-SHA256 签名，
 * 签名内容为「时间戳 + 换行 + 请求体」。响应统一为 { code, message, data }，code 为 0 表示成功。
 * 不跟随重定向，拒绝解析到链路本地（含云厂商元数据）、组播和通配地址的目标。HttpClient 默认不走系统代理。
 */
@Service
public class BusinessClient {

    private static final Logger log = LoggerFactory.getLogger(BusinessClient.class);

    private static final int MESSAGE_MAX_LENGTH = 200;

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    /** 一次调用的目标；secret 为明文 */
    public record Target(String url, String secret, int timeoutMs, String operator) {
    }

    /** 调用 path（/admin/query 或 /admin/queries），返回响应里的 data；失败抛 DATA_SOURCE_FAILED 并附带原因 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> call(Target target, String path, Object body) {
        URI uri = URI.create(target.url().replaceAll("/+$", "") + path);
        requireAllowedHost(uri);
        String json = JsonUtils.object2String(body);
        String timestamp = String.valueOf(System.currentTimeMillis());
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(target.timeoutMs()))
                .header("Content-Type", "application/json")
                .header("X-Admin-Timestamp", timestamp)
                .header("X-Admin-Signature", sign(target.secret(), timestamp + "\n" + json))
                .header("X-Admin-Operator", target.operator())
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw failed(uri, "无法连接（" + exception.getClass().getSimpleName() + (exception.getMessage() == null ? "" : "：" + exception.getMessage()) + "）");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failed(uri, "请求被中断");
        }
        if (response.statusCode() != 200) {
            throw failed(uri, "HTTP " + response.statusCode());
        }
        Map<String, Object> result;
        try {
            result = JsonUtils.string2Object(response.body(), Map.class);
        } catch (RuntimeException exception) {
            throw failed(uri, "返回的不是 JSON");
        }
        Object code = result.get("code");
        if (!(code instanceof Number number) || number.intValue() != 0) {
            throw failed(uri, "业务返回 code=" + code + (result.get("message") == null ? "" : "，" + result.get("message")));
        }
        Object data = result.get("data");
        if (!(data instanceof Map)) {
            throw failed(uri, "返回缺少 data 对象");
        }
        return (Map<String, Object>) data;
    }

    /** 签名：HMAC-SHA256(密钥, 时间戳 + "\n" + 请求体)，小写十六进制 */
    public static String sign(String secret, String content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("签名失败", exception);
        }
    }

    private static void requireAllowedHost(URI uri) {
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (address.isLinkLocalAddress() || address.isMulticastAddress() || address.isAnyLocalAddress()) {
                    throw failed(uri, "不允许访问该地址");
                }
            }
        } catch (UnknownHostException exception) {
            throw failed(uri, "无法解析主机名");
        }
    }

    private static BusinessException failed(URI uri, String reason) {
        String text = reason.length() > MESSAGE_MAX_LENGTH ? reason.substring(0, MESSAGE_MAX_LENGTH) + "…" : reason;
        log.warn("业务数据源请求失败 {}: {}", uri, text);
        return new BusinessException(ErrorCode.DATA_SOURCE_FAILED, text);
    }
}
