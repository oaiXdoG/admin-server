package com.oaixdog.adminbackground.protocol;

import com.zfoo.protocol.anno.Protocol;
import lombok.Getter;
import lombok.Setter;

/** typeId 为空表示新建；code 为业务类型编码，可为空 */
@Getter
@Setter
@Protocol(id = 3123)
public class ServerProcessTypeSaveRequest {

    private Long projectId;
    private Long typeId;
    private String name;
    private String code;
}
