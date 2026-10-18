package com.oaixdog.adminbackground.protocol;

import com.zfoo.protocol.anno.Protocol;
import lombok.Getter;
import lombok.Setter;

/** 用表单里的值测试：sourceId 有值且 secret 留空时使用已保存的密钥 */
@Getter
@Setter
@Protocol(id = 3082)
public class DataSourceTestRequest {

    private Long projectId;
    private Long sourceId;
    /** http（默认）/ discover */
    private String type;
    /** 按业务类型发现的业务类型编码，type=discover 时必填 */
    private String serviceType;
    /** 按业务类型发现的连接方式：inner（默认）/ public */
    private String network;
    private String url;
    private String secret;
    private Integer timeoutMs;
}
