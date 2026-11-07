package com.oaixdog.adminbackground.protocol;

import com.zfoo.protocol.anno.Protocol;
import lombok.Getter;
import lombok.Setter;

/** backup 为 backups 返回的名称，返回 taskId */
@Getter
@Setter
@Protocol(id = 3117)
public class OpsAppRollbackRequest {

    private Long appId;
    private String backup;
}
