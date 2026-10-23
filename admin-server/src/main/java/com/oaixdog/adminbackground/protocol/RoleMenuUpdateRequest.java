package com.oaixdog.adminbackground.protocol;

import com.zfoo.protocol.anno.Protocol;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@Protocol(id = 3013)
public class RoleMenuUpdateRequest {

    private Long roleId;
    private List<Long> menuIds;
}
