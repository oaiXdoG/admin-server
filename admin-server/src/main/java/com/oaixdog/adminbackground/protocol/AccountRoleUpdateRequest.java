package com.oaixdog.adminbackground.protocol;

import com.zfoo.protocol.anno.Protocol;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@Protocol(id = 3045)
public class AccountRoleUpdateRequest {

    private Long accountId;
    private List<Long> roleIds;
}
