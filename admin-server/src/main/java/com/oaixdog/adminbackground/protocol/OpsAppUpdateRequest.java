package com.oaixdog.adminbackground.protocol;

import com.zfoo.protocol.anno.Protocol;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Protocol(id = 3101)
public class OpsAppUpdateRequest {

    private Long appId;
    private String name;
    private Long hostId;
    private String dir;
    private String remark;
}
