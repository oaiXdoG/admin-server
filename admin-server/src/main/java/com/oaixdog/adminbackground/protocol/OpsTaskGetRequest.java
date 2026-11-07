package com.oaixdog.adminbackground.protocol;

import com.zfoo.protocol.anno.Protocol;
import lombok.Getter;
import lombok.Setter;

/** output 为 offset 之后的输出 */
@Getter
@Setter
@Protocol(id = 3119)
public class OpsTaskGetRequest {

    private Long taskId;
    private Integer offset;
}
