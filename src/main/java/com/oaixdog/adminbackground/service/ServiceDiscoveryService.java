package com.oaixdog.adminbackground.service;

import com.oaixdog.adminbackground.entity.DataSource;
import com.zfoo.protocol.util.StringUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.BinaryOperator;
import java.util.stream.Collectors;

/**
 * 按业务类型发现：读取本项目指定业务类型服务的上报（NodeInfoReportService，规则见 docs/服务发现改造.md），只取在线且 gmPort 大于 0 的记录。
 * 查询接口为上报的 innerHost:gmPort（连接方式为公网时用上报的 host，即公网地址）。logic（游戏服）按区，key 为区号；
 * 其他类型按节点，key 为 nodeId（为空时用 instanceId）。同一个 key 有多条记录时取最近上报的一条。
 */
@Service
@RequiredArgsConstructor
public class ServiceDiscoveryService {

    private final NodeInfoReportService nodeInfoReportService;

    /** 可查询目标：key 为区号或节点标识；zoneId 只有 logic 有值 */
    public record Target(String key, Integer zoneId, String nodeId, String url) {
    }

    /** 当前可查询的目标，logic 按区号数字排序，其他按 key 排序；没有上报时返回空列表 */
    public List<Target> targets(String appKey, String serviceType, boolean publicNetwork) {
        boolean logic = DataSource.SERVICE_TYPE_LOGIC.equals(serviceType);
        BinaryOperator<NodeInfoReportService.Node> latest = (a, b) -> a.reportTime() >= b.reportTime() ? a : b;
        Map<String, NodeInfoReportService.Node> nodes = nodeInfoReportService.onlineNodes(appKey).stream()
                .filter(node -> serviceType.equals(node.type()) && node.gmPort() > 0 && StringUtils.isNotBlank(key(node, logic)))
                .collect(Collectors.toMap(node -> key(node, logic), node -> node, latest));
        Comparator<Target> order = logic ? Comparator.comparingInt(Target::zoneId) : Comparator.comparing(Target::key);
        return nodes.entrySet().stream().map(entry -> target(entry.getKey(), entry.getValue(), logic, publicNetwork)).sorted(order).toList();
    }

    private static String key(NodeInfoReportService.Node node, boolean logic) {
        if (logic) {
            return node.serverId() == null ? null : String.valueOf(node.serverId());
        }
        return StringUtils.isBlank(node.nodeId()) ? node.instanceId() : node.nodeId();
    }

    private static Target target(String key, NodeInfoReportService.Node node, boolean logic, boolean publicNetwork) {
        Integer zoneId = logic ? node.serverId() : null;
        String nodeId = node.nodeId() == null ? "" : node.nodeId();
        String url = "http://" + (publicNetwork ? node.host() : node.innerHost()) + ":" + node.gmPort();
        return new Target(key, zoneId, nodeId, url);
    }
}
