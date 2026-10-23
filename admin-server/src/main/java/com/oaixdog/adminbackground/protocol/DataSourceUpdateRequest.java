package com.oaixdog.adminbackground.protocol;

import com.zfoo.protocol.anno.Protocol;
import lombok.Getter;
import lombok.Setter;

/** secret 留空表示沿用已保存的密钥 */
@Getter
@Setter
@Protocol(id = 3080)
public class DataSourceUpdateRequest {

    private Long sourceId;
    private String name;
    private String url;
    private String secret;
    private Integer timeoutMs;
    private String remark;
}
