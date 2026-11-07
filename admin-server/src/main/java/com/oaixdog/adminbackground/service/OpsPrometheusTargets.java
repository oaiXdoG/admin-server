package com.oaixdog.adminbackground.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oaixdog.adminbackground.config.ApplicationProperties;
import com.oaixdog.adminbackground.entity.OpsHost;
import com.oaixdog.adminbackground.mapper.OpsHostMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

/**
 * 把运维中心的主机列表写给 Prometheus（file_sd）：ops.prometheus.targets-dir 下的 node.json（node_exporter）、
 * process.json（process-exporter），端口为 ops.exporter.node-port、ops.exporter.process-port。每个目标带 host（IP）、host_id 标签，Grafana 面板按 host 筛选。
 * 启动时和主机增删改后重写；Prometheus 自动重新读取，不用重启。目录为空时不写。
 */
@Service
@RequiredArgsConstructor
public class OpsPrometheusTargets implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(OpsPrometheusTargets.class);

    private final ApplicationProperties properties;
    private final OpsHostMapper opsHostMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void afterPropertiesSet() {
        write();
    }

    public synchronized void write() {
        String dir = properties.getOpsPrometheusTargetsDir();
        if (dir == null || dir.isBlank()) {
            return;
        }
        List<OpsHost> hosts = opsHostMapper.selectList(Wrappers.<OpsHost>lambdaQuery().orderByAsc(OpsHost::getId));
        try {
            Path base = Path.of(dir);
            Files.createDirectories(base);
            writeFile(base.resolve("node.json"), targets(hosts, properties.getOpsNodeExporterPort()));
            writeFile(base.resolve("process.json"), targets(hosts, properties.getOpsProcessExporterPort()));
            log.info("已更新 Prometheus 的主机列表，{} 台，目录={}", hosts.size(), base.toAbsolutePath());
        } catch (IOException e) {
            log.error("写入 Prometheus 的主机列表失败，目录={}", dir, e);
        }
    }

    private static List<Map<String, Object>> targets(List<OpsHost> hosts, int port) {
        return hosts.stream().map(host -> Map.<String, Object>of(
                "targets", List.of(host.getIp() + ":" + port),
                "labels", Map.of("host", host.getIp(), "host_id", String.valueOf(host.getId())))).toList();
    }

    /** 先写临时文件再替换，Prometheus 不会读到写了一半的文件 */
    private void writeFile(Path file, Object content) throws IOException {
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, objectMapper.writeValueAsString(content), StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
