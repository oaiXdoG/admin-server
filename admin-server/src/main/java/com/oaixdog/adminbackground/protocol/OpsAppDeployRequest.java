package com.oaixdog.adminbackground.protocol;

import com.zfoo.protocol.anno.Protocol;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Protocol(id = 3110)
public class OpsAppDeployRequest {

    private Long appId;
    private String uploadId;
    private Boolean overwriteConf;
}
