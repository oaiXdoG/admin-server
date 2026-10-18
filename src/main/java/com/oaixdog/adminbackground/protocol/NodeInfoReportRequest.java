package com.oaixdog.adminbackground.protocol;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.zfoo.protocol.anno.Protocol;
import lombok.Getter;
import lombok.Setter;

/**
 * 业务服（登录服、回调服、游戏服等）定时上报的节点信息，不认识的字段忽略，节点新增字段时后台不用先升级（规则见 docs/服务发现改造.md）。
 * type 必填（login / callback / logic 等，对应进程类型编码）；serverId 只有 type=logic 时必填，表示区号。
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
@Protocol(id = 3133)
public class NodeInfoReportRequest {

    private String type;
    private String nodeId;
    private Integer serverId;
    private String instanceId;
    private String host;
    private Integer port;
    private String innerHost;
    private Integer gmPort;
    /** 原始请求体，由后台解析请求时填入，不从请求体读取 */
    @JsonIgnore
    private String raw;
}
