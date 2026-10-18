package com.oaixdog.adminbackground.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * 按区自动发现：读取本项目游戏服的上报（NodeInfoReportService，规则见 danmu-login docs/service-discovery.md 第 7 节），
 * 每个区的查询接口为上报的 innerHost:gmPort（连接方式为公网时用上报的 host，即公网地址），gmPort 为 0 的区不提供查询接口。
 */
@Service
@RequiredArgsConstructor
public class ZoneDiscoveryService {

    private final NodeInfoReportService nodeInfoReportService;

    /** 区 ID、查询接口地址 */
    public record Zone(int zoneId, String url) {
    }

    /** 当前可查询的区（在线且 gmPort 大于 0），按区 ID 排序；没有上报时返回空列表 */
    public List<Zone> zones(String appKey, boolean publicNetwork) {
        return nodeInfoReportService.onlineNodes(appKey).stream().filter(node -> node.gmPort() > 0)
                .map(node -> new Zone(node.serverId(), "http://" + (publicNetwork ? node.host() : node.innerHost()) + ":" + node.gmPort()))
                .sorted(Comparator.comparingInt(Zone::zoneId)).toList();
    }
}
