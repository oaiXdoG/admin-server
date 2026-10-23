package com.oaixdog.adminbackground.protocol;

import com.zfoo.protocol.anno.Protocol;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Protocol(id = 3056)
public class SystemSettingUpdateRequest {

    private String appName;
    private String logo;
    private String favicon;
    private String loginTitle;
    private String loginDescription;
    private String copyright;
}
