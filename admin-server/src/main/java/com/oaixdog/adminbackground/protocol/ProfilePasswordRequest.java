package com.oaixdog.adminbackground.protocol;

import com.zfoo.protocol.anno.Protocol;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Protocol(id = 3054)
public class ProfilePasswordRequest {

    private String oldPassword;
    private String newPassword;
}
