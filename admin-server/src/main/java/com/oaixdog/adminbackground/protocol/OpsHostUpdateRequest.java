package com.oaixdog.adminbackground.protocol;

import com.zfoo.protocol.anno.Protocol;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Protocol(id = 3097)
public class OpsHostUpdateRequest {

    private Long hostId;
    private String ip;
    private Integer sshPort;
    private String remark;
}
