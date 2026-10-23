package com.oaixdog.adminbackground.protocol;

import com.zfoo.protocol.anno.Protocol;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Protocol(id = 3020)
public class AccountUpdateRequest {

    private Long accountId;
    private String username;
    private String password;
    private String remark;
}
