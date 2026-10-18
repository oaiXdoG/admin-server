package com.oaixdog.adminbackground.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.oaixdog.adminbackground.entity.DataSource;
import com.oaixdog.adminbackground.entity.Project;
import com.oaixdog.adminbackground.exception.BusinessException;
import com.oaixdog.adminbackground.exception.ErrorCode;
import com.oaixdog.adminbackground.mapper.DataSourceMapper;
import com.oaixdog.adminbackground.mapper.ProjectMapper;
import com.oaixdog.adminbackground.protocol.NodeInfoReportRequest;
import com.zfoo.protocol.util.StringUtils;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 节点信息 HTTP 上报（规则见 danmu-login docs/service-discovery.md 第 7 节）：按 App Key 找项目，用项目下任一「按区自动发现」数据源的密钥验签
 * （HMAC-SHA256(密钥, 时间戳 + "\n" + SHA-256(请求体))，时间戳与后台相差不超过 5 分钟）。
 * 按 App Key → serverId 保存在内存里，每次覆盖、不删除，30 秒内有上报视为在线；后台重启后由节点重新上报补齐，多实例部署时要改为放 Redis。
 */
@Service
@RequiredArgsConstructor
public class NodeInfoReportService {

    private static final Logger log = LoggerFactory.getLogger(NodeInfoReportService.class);
    private static final long SIGNATURE_WINDOW = 5 * 60_000L;
    private static final long ONLINE_WINDOW = 30_000L;
    private static final long WARN_INTERVAL = 60_000L;
    private static final int WARN_KEY_LIMIT = 10_000;

    private final ProjectMapper projectMapper;
    private final DataSourceMapper dataSourceMapper;
    private final SecretCipher secretCipher;

    private final ConcurrentHashMap<String, ConcurrentHashMap<Integer, Node>> nodes = new ConcurrentHashMap<>();
    /** App Key + 来源 IP → 上次打印拒绝警告的时间 */
    private final Map<String, Long> warnTimes = new ConcurrentHashMap<>();

    public record Node(int serverId, String instanceId, String host, int port, String innerHost, int gmPort, long reportTime) {
    }

    public void report(String appKey, String timestampHeader, String signature, String bodyDigest, NodeInfoReportRequest request, String ip) {
        String reason = deniedReason(appKey, timestampHeader, signature, bodyDigest);
        if (reason != null) {
            warn(appKey, request.getServerId(), ip, reason);
            throw new BusinessException(ErrorCode.NODE_REPORT_DENIED);
        }
        if (!valid(request)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        Node node = new Node(request.getServerId(), request.getInstanceId(), request.getHost(), request.getPort(), request.getInnerHost(), request.getGmPort(),
                System.currentTimeMillis());
        nodes.computeIfAbsent(appKey, key -> new ConcurrentHashMap<>()).put(node.serverId(), node);
    }

    /** 该项目在线的节点（30 秒内有上报），未排序 */
    public List<Node> onlineNodes(String appKey) {
        Map<Integer, Node> projectNodes = nodes.get(appKey);
        if (projectNodes == null) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        return projectNodes.values().stream().filter(node -> now - node.reportTime() <= ONLINE_WINDOW).toList();
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
        List<DataSource> sources = dataSourceMapper.selectList(Wrappers.<DataSource>lambdaQuery().eq(DataSource::getProjectId, project.getId())
                .eq(DataSource::getType, DataSource.TYPE_ZONE));
        if (sources.isEmpty()) {
            return "项目没有按区自动发现的数据源";
        }
        byte[] actual = signature.getBytes(StandardCharsets.UTF_8);
        for (DataSource source : sources) {
            String secret = secretCipher.decrypt(source.getSecret());
            if (secret != null && MessageDigest.isEqual(BusinessClient.sign(secret, timestamp + "\n" + bodyDigest).getBytes(StandardCharsets.UTF_8), actual)) {
                return null;
            }
        }
        return "签名错误";
    }

    private static boolean valid(NodeInfoReportRequest request) {
        return request.getServerId() != null && request.getServerId() > 0 && StringUtils.isNotBlank(request.getInstanceId())
                && StringUtils.isNotBlank(request.getHost()) && StringUtils.isNotBlank(request.getInnerHost()) && validPort(request.getPort())
                && validPort(request.getGmPort());
    }

    private static boolean validPort(Integer port) {
        return port != null && port >= 0 && port <= 65535;
    }

    /** 同一 App Key + 来源 IP 每分钟最多打印一次；伪造大量 App Key 时清掉过期的记录，避免无限增长 */
    private void warn(String appKey, Integer serverId, String ip, String reason) {
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
        log.warn("节点信息上报验签失败: appKey={}, serverId={}, ip={}, reason={}", appKey, serverId, ip, reason);
    }
}
