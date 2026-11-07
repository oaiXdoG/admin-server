package com.oaixdog.adminbackground.protocol;

import com.zfoo.protocol.anno.Protocol;
import lombok.Getter;
import lombok.Setter;

/** sshPort 为空时 22 */
@Getter
@Setter
@Protocol(id = 3113)
public class OpsHostCreateRequest {

    private String ip;
    private Integer sshPort;
    private String remark;
}
