package com.oaixdog.adminbackground.protocol;

import com.zfoo.protocol.anno.Protocol;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Protocol(id = 3079)
public class DataSourceCreateRequest {

    private String name;
    private String url;
    private String secret;
    private Integer timeoutMs;
    private String remark;
}
