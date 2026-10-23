package com.oaixdog.adminbackground.protocol;

import com.zfoo.protocol.anno.Protocol;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Protocol(id = 3005)
public class AccountCreateRequest {

    private String account;
    private String password;
    private String username;
    private String remark;
}
