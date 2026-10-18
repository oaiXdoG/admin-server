package com.oaixdog.adminbackground.protocol;

import com.zfoo.protocol.anno.Protocol;
import lombok.Getter;
import lombok.Setter;

/** 业务服上报配置：App Key、上报地址、上报密钥（手动配置时用） */
@Getter
@Setter
@Protocol(id = 3135)
public class ServerReportConfigRequest {

    private Long projectId;
}
