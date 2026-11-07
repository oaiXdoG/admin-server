package com.oaixdog.adminbackground.protocol;

import com.zfoo.protocol.anno.Protocol;
import lombok.Getter;
import lombok.Setter;

/** file 为相对 conf 的路径 */
@Getter
@Setter
@Protocol(id = 3106)
public class OpsAppConfigGetRequest {

    private Long appId;
    private String file;
}
