package com.oaixdog.adminbackground.protocol;

import com.zfoo.protocol.anno.Protocol;
import lombok.Getter;
import lombok.Setter;

/** 监控页选择程序用，不查询 host */
@Getter
@Setter
@Protocol(id = 3112)
public class OpsAppOptionsRequest {

    private Long hostId;
}
