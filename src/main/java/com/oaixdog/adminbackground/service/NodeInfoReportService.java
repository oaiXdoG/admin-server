package com.oaixdog.adminbackground.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.oaixdog.adminbackground.entity.Project;
import com.oaixdog.adminbackground.exception.BusinessException;
import com.oaixdog.adminbackground.exception.ErrorCode;
import com.oaixdog.adminbackground.mapper.ProjectMapper;
import com.oaixdog.adminbackground.protocol.NodeInfoReportRequest;
import com.zfoo.protocol.util.JsonUtils;
import com.zfoo.protocol.util.StringUtils;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 业务服节点信息 HTTP 上报（规则见 docs/服务发现改造.md）：按 App Key 找项目，用项目上报密钥验签
 * （HMAC-SHA256(密钥, 时间戳 + "\n" + SHA-256(请求体))，时间戳与后台相差不超过 5 分钟）。
 * 按 App Key → instanceId 保存在内存里，每次覆盖，30 秒内有上报视为在线；超过 1 天没有上报的记录在该项目下次收到上报时清掉。
 * 后台托管的进程 instanceId 为后台进程 ID（Agent 注入 ADM_INSTANCE_ID）。后台重启后由节点重新上报补齐，多实例部署时要改为放 Redis。
 */
@Service
@RequiredArgsConstructor
public class NodeInfoReportService {

    public static final long ONLINE_WINDOW = 30_000L;
    private static final Logger log = LoggerFactory.getLogger(NodeInfoReportService.class);
    private static final long SIGNATURE_WINDOW = 5 * 60_000L;
    private static final long EXPIRE_WINDOW = 24 * 3600_000L;
    private static final long WARN_INTERVAL = 60_000L;
    private static final int WARN_KEY_LIMIT = 10_000;
    private static final String TYPE_LOGIC = "logic";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ProjectMapper projectMapper;
    private final SecretCipher secretCipher;

    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Node>> nodes = new ConcurrentHashMap<>();
    /** App Key + 来源 IP → 上次打印拒绝警告的时间 */
    private final Map<String, Long> warnTimes = new ConcurrentHashMap<>();

    /** raw 为原始上报内容（请求体 JSON 对象） */
    public record Node(String type, String nodeId, String instanceId, Integer serverId, String host, int port, String innerHost, int gmPort,
                       Map<String, Object> raw, long reportTime) {

        public boolean online(long now) {
            return now - reportTime <= ONLINE_WINDOW;
        }
    }

    public void report(String appKey, String timestampHeader, String signature, String bodyDigest, NodeInfoReportRequest request, String ip) {
        String reason = deniedReason(appKey, timestampHeader, signature, bodyDigest);
        if (reason != null) {
            warn(appKey, request.getInstanceId(), ip, reason);
            throw new BusinessException(ErrorCode.NODE_REPORT_DENIED);
        }
        if (!valid(request)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        long now = System.currentTimeMillis();
        String nodeId = request.getNodeId() == null ? "" : request.getNodeId();
        Node node = new Node(request.getType(), nodeId, request.getInstanceId(), request.getServerId(), request.getHost(), request.getPort(), request.getInnerHost(),
                request.getGmPort(), raw(request.getRaw()), now);
        ConcurrentHashMap<String, Node> projectNodes = nodes.computeIfAbsent(appKey, key -> new ConcurrentHashMap<>());
        projectNodes.values().removeIf(old -> now - old.reportTime() > EXPIRE_WINDOW);
        projectNodes.put(node.instanceId(), node);
    }

    /** 该项目在线的节点（30 秒内有上报），未排序 */
    public List<Node> onlineNodes(String appKey) {
        long now = System.currentTimeMillis();
        return nodes(appKey).stream().filter(node -> node.online(now)).toList();
    }

    /** 该项目保存着的全部节点（含离线），未排序 */
    public List<Node> nodes(String appKey) {
        Map<String, Node> projectNodes = appKey == null ? null : nodes.get(appKey);
        return projectNodes == null ? List.of() : List.copyOf(projectNodes.values());
    }

    /** 项目上报密钥明文；还没有（或因加密密钥变更无法解密）时生成一个并保存 */
    public String reportSecret(long projectId) {
        Project project = projectMapper.selectById(projectId);
        String secret = secretCipher.decrypt(project.getReportSecret());
        if (secret != null) {
            return secret;
        }
        String generated = generateSecret();
        String old = project.getReportSecret() == null ? "" : project.getReportSecret();
        int updated = projectMapper.update(null, Wrappers.<Project>lambdaUpdate().set(Project::getReportSecret, secretCipher.encrypt(generated))
                .eq(Project::getId, projectId).eq(Project::getReportSecret, old));
        return updated > 0 ? generated : secretCipher.decrypt(projectMapper.selectById(projectId).getReportSecret());
    }

    /** 重新生成项目上报密钥，返回明文；之前的密钥立即失效 */
    public String resetReportSecret(long projectId) {
        String generated = generateSecret();
        projectMapper.update(null, Wrappers.<Project>lambdaUpdate().set(Project::getReportSecret, secretCipher.encrypt(generated)).eq(Project::getId, projectId));
        return generated;
    }

    private static String generateSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> raw(String body) {
        try {
            return StringUtils.isBlank(body) ? Map.of() : JsonUtils.string2Object(body, LinkedHashMap.class);
        } catch (RuntimeException exception) {
            return Map.of();
        }
    }

    /** 验签通过返回 null，否则返回拒绝原因（只用于日志） */
    private String deniedReason(String appKey, String timestampHeader, String signature, String bodyDigest) {
        if (StringUtils.isBlank(appKey) || StringUtils.isBlank(signature) || bodyDigest == null) {
            return "缺少签名请求头";
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(timestampHeader);
        } catch (NumberFormatException exception) {
            return "时间戳格式不对";
        }
        if (Math.abs(System.currentTimeMillis() - timestamp) > SIGNATURE_WINDOW) {
            return "时间戳与后台相差超过 5 分钟";
        }
        Project project = projectMapper.selectOne(Wrappers.<Project>lambdaQuery().eq(Project::getAppKey, appKey));
        if (project == null) {
            return "App Key 不存在";
        }
        String secret = secretCipher.decrypt(project.getReportSecret());
        if (secret == null) {
            return "项目还没有上报密钥";
        }
        byte[] expected = BusinessClient.sign(secret, timestamp + "\n" + bodyDigest).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.UTF_8)) ? null : "签名错误";
    }

    private static boolean valid(NodeInfoReportRequest request) {
        boolean serverIdValid = !TYPE_LOGIC.equals(request.getType()) || request.getServerId() != null && request.getServerId() > 0;
        return StringUtils.isNotBlank(request.getType()) && serverIdValid && StringUtils.isNotBlank(request.getInstanceId())
                && StringUtils.isNotBlank(request.getHost()) && StringUtils.isNotBlank(request.getInnerHost()) && validPort(request.getPort())
                && validPort(request.getGmPort());
    }

    private static boolean validPort(Integer port) {
        return port != null && port >= 0 && port <= 65535;
    }

    /** 同一 App Key + 来源 IP 每分钟最多打印一次；伪造大量 App Key 时清掉过期的记录，避免无限增长 */
    private void warn(String appKey, String instanceId, String ip, String reason) {
        long now = System.currentTimeMillis();
        if (warnTimes.size() > WARN_KEY_LIMIT) {
            warnTimes.values().removeIf(time -> now - time >= WARN_INTERVAL);
        }
        String key = appKey + "|" + ip;
        Long last = warnTimes.get(key);
        if (last != null && now - last < WARN_INTERVAL) {
            return;
        }
        warnTimes.put(key, now);
        log.warn("节点信息上报验签失败: appKey={}, instanceId={}, ip={}, reason={}", appKey, instanceId, ip, reason);
    }
}
