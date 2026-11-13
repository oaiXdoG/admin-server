package com.oaixdog.adminbackground.protocol;

import com.zfoo.protocol.anno.Protocol;
import lombok.Getter;
import lombok.Setter;

/** sshPort 为空时为 22 */
@Getter
@Setter
@Protocol(id = 3101)
public class OpsAppUpdateRequest {

    private Long appId;
    private String name;
    private String ip;
    private Integer sshPort;
    private String dir;
    private String remark;
}
