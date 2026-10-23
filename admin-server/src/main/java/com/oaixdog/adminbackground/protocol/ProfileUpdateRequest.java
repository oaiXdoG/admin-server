package com.oaixdog.adminbackground.protocol;

import com.zfoo.protocol.anno.Protocol;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Protocol(id = 3053)
public class ProfileUpdateRequest {

    private String username;
    private String avatar;
    private String remark;
}
