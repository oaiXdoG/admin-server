package com.oaixdog.adminbackground.service;

import com.oaixdog.protocol.admin.ServerReport;
import com.zfoo.event.anno.EventReceiver;
import com.zfoo.net.core.event.ServerSessionInactiveEvent;
import com.zfoo.net.packet.common.Message;
import com.zfoo.net.session.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 各服务通过 ServerReport 上报的服务类型和区服 ID，按连接保存在内存里，连接断开即移除。
 */
@Service
public class ServerRegistryService {

    private static final Logger log = LoggerFactory.getLogger(ServerRegistryService.class);

    private final Map<Long, ReportedServer> servers = new ConcurrentHashMap<>();

    public Message report(Session session, ServerReport report) {
        String serverType = report.getServerType();
        if (serverType == null || serverType.isBlank() || report.getZoneId() < 0) {
            log.warn("服务上报参数错误，serverType={}，zoneId={}，address={}", serverType, report.getZoneId(), session.getChannel().remoteAddress());
            return Message.valueError("serverType 不能为空，zoneId 不能小于 0");
        }
        ReportedServer server = new ReportedServer(serverType, report.getZoneId(),
                String.valueOf(session.getChannel().remoteAddress()), System.currentTimeMillis());
        ReportedServer old = servers.put(session.getSid(), server);
        if (old == null) {
            log.info("服务上报，serverType={}，zoneId={}，address={}", server.serverType(), server.zoneId(), server.address());
        }
        return Message.valueSuccess("");
    }

    /**
     * 当前在线的服务，按服务类型、区服 ID 排序。
     */
    public List<ReportedServer> list() {
        return servers.values().stream()
                .sorted(Comparator.comparing(ReportedServer::serverType).thenComparingInt(ReportedServer::zoneId))
                .toList();
    }

    public List<ReportedServer> listByType(String serverType) {
        return list().stream().filter(it -> it.serverType().equals(serverType)).toList();
    }

    @EventReceiver
    public void onServerSessionInactiveEvent(ServerSessionInactiveEvent event) {
        ReportedServer server = servers.remove(event.getSession().getSid());
        if (server != null) {
            log.info("服务断开，serverType={}，zoneId={}，address={}", server.serverType(), server.zoneId(), server.address());
        }
    }

    public record ReportedServer(String serverType, int zoneId, String address, long reportTime) {}
}
