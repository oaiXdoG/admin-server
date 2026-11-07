package com.oaixdog.adminbackground.protocol;

import com.zfoo.protocol.anno.Protocol;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Protocol(id = 3107)
public class OpsAppConfigSaveRequest {

    private Long appId;
    private String file;
    private String content;
    /** 保存后重启（需要 ops_app:control） */
    private Boolean restart;
}
