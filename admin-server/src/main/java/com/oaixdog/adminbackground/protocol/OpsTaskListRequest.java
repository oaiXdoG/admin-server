package com.oaixdog.adminbackground.protocol;

import com.zfoo.protocol.anno.Protocol;
import lombok.Getter;
import lombok.Setter;

/** hostId、appId 为空时不过滤 */
@Getter
@Setter
@Protocol(id = 3118)
public class OpsTaskListRequest {

    private Integer page;
    private Integer pageSize;
    private Long hostId;
    private Long appId;
}
