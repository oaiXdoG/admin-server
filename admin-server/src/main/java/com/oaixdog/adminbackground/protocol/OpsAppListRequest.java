package com.oaixdog.adminbackground.protocol;

import com.zfoo.protocol.anno.Protocol;
import lombok.Getter;
import lombok.Setter;

/** ip 为空时返回全部程序 */
@Getter
@Setter
@Protocol(id = 3099)
public class OpsAppListRequest {

    private String ip;
}
