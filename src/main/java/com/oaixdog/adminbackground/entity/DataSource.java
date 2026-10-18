package com.oaixdog.adminbackground.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 数据源：业务服务器按约定提供的查询接口，secret 为加密保存的签名密钥 */
@Getter
@Setter
@TableName("data_source")
public class DataSource {

    public static final String TYPE_HTTP = "http";
    /** 按区自动发现：地址取自游戏服上报（NodeInfoReportService），url 为空；密钥同时用于校验游戏服上报的签名 */
    public static final String TYPE_ZONE = "zone";
    /** 按区自动发现时的连接方式：inner 用上报的内网地址，public 用上报的公网地址（后台和游戏服不在同一个内网时） */
    public static final String NETWORK_INNER = "inner";
    public static final String NETWORK_PUBLIC = "public";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private String name;
    private String type;
    private String url;
    private String network;
    private String secret;
    private Integer timeoutMs;
    private String remark;
    private Long createdAt;
    private Long updatedAt;
}
