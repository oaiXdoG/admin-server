package com.oaixdog.adminbackground.protocol;

import com.zfoo.protocol.anno.Protocol;
import lombok.Getter;
import lombok.Setter;

/** action 为 start、stop、restart */
@Getter
@Setter
@Protocol(id = 3103)
public class OpsAppControlRequest {

    private Long appId;
    private String action;
}
