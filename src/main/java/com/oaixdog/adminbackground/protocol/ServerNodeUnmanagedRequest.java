package com.oaixdog.adminbackground.protocol;

import com.zfoo.protocol.anno.Protocol;
import lombok.Getter;
import lombok.Setter;

/** 未托管服务：本项目上报里 instanceId 对应不到本项目任何进程的记录 */
@Getter
@Setter
@Protocol(id = 3134)
public class ServerNodeUnmanagedRequest {

    private Long projectId;
}
