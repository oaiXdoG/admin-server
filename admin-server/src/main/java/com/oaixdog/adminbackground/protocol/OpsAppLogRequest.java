package com.oaixdog.adminbackground.protocol;

import com.zfoo.protocol.anno.Protocol;
import lombok.Getter;
import lombok.Setter;

/** lines 默认 200，上限 2000；file 为 server.log 或 error.log，默认 server.log */
@Getter
@Setter
@Protocol(id = 3104)
public class OpsAppLogRequest {

    private Long appId;
    private Integer lines;
    private String file;
}
