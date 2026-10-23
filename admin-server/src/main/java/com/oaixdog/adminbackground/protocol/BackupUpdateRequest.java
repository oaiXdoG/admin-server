package com.oaixdog.adminbackground.protocol;

import com.zfoo.protocol.anno.Protocol;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Protocol(id = 3069)
public class BackupUpdateRequest {

    private Boolean enabled;
    private String time;
    private Integer keep;
}
