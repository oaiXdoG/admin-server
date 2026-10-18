package com.oaixdog.adminbackground.protocol;

import com.zfoo.protocol.anno.Protocol;
import lombok.Getter;
import lombok.Setter;

/** 重新生成项目上报密钥 */
@Getter
@Setter
@Protocol(id = 3136)
public class ServerReportConfigResetRequest {

    private Long projectId;
}
