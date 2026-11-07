package com.oaixdog.adminbackground.protocol;

import com.zfoo.protocol.anno.Protocol;
import lombok.Getter;
import lombok.Setter;

/** index 从 0 开始按顺序发送，data 为这一片的 base64（前端每片 512KB） */
@Getter
@Setter
@Protocol(id = 3109)
public class OpsAppUploadChunkRequest {

    private Long appId;
    private String uploadId;
    private Integer index;
    private String data;
}
