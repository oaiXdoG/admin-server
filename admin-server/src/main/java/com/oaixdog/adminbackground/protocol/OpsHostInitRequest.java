package com.oaixdog.adminbackground.protocol;

import com.zfoo.protocol.anno.Protocol;
import lombok.Getter;
import lombok.Setter;

/** 返回 taskId */
@Getter
@Setter
@Protocol(id = 3115)
public class OpsHostInitRequest {

    private Long hostId;
}
