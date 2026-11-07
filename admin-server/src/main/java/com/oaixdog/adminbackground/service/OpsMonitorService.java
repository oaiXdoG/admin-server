package com.oaixdog.adminbackground.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.oaixdog.adminbackground.entity.OpsApp;
import com.oaixdog.adminbackground.mapper.OpsAppMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 主机监控：页面嵌入 Grafana 的面板（数据由 Prometheus 拉取各主机的 node_exporter、process-exporter），
 * 管理后台只提供主机、程序的下拉选项。需要 ops_monitor。
 */
@Service
@RequiredArgsConstructor
public class OpsMonitorService {

    private static final String MENU = "ops_monitor";

    private final PermissionService permissionService;
    private final OpsHostService opsHostService;
    private final OpsAppMapper opsAppMapper;

    /** 主机上的程序，只有 id、名称、目录、备注；程序管理页面也可以用 */
    public Map<String, Object> appOptions(String authorization, Long hostId) {
        permissionService.requireAnyMenu(authorization, MENU, "ops_app");
        List<Map<String, Object>> list = opsAppMapper.selectList(Wrappers.<OpsApp>lambdaQuery()
                        .eq(OpsApp::getHostId, opsHostService.requireHost(hostId).getId())
                        .orderByAsc(OpsApp::getName).orderByAsc(OpsApp::getId)).stream()
                .map(app -> Map.<String, Object>of("id", app.getId(), "name", app.getName(), "dir", app.getDir(), "remark", app.getRemark()))
                .toList();
        return Map.of("list", list);
    }
}
