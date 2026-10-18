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
    /** 按业务类型发现：地址取自 serviceType 类型服务的上报（NodeInfoReportService），url 为空；密钥只用于调用服务的 GM 接口，上报验签用项目上报密钥 */
    public static final String TYPE_DISCOVER = "discover";
    /** 业务类型 logic（游戏服）按区发现，其他类型按节点发现 */
    public static final String SERVICE_TYPE_LOGIC = "logic";
    /** 按业务类型发现时的连接方式：inner 用上报的内网地址，public 用上报的公网地址（后台和服务不在同一个内网时） */
    public static final String NETWORK_INNER = "inner";
    public static final String NETWORK_PUBLIC = "public";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private String name;
    private String type;
    /** 按业务类型发现的业务类型编码（server_process_type.code，即上报的 type）；固定地址为空串 */
    private String serviceType;
    private String url;
    private String network;
    private String secret;
    private Integer timeoutMs;
    private String remark;
    private Long createdAt;
    private Long updatedAt;
}
