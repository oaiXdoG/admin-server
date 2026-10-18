package com.oaixdog.adminbackground.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.zfoo.protocol.anno.Protocol;
import lombok.Getter;
import lombok.Setter;

/** 业务节点（游戏服等）定时上报的节点信息，不认识的字段忽略，节点新增字段时后台不用先升级（规则见 danmu-login docs/service-discovery.md 第 7 节） */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
@Protocol(id = 3133)
public class NodeInfoReportRequest {

    private Integer serverId;
    private String instanceId;
    private String host;
    private Integer port;
    private String innerHost;
    private Integer gmPort;
}
