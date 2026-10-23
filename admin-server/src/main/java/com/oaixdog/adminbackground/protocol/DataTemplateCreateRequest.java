package com.oaixdog.adminbackground.protocol;

import com.zfoo.protocol.anno.Protocol;
import lombok.Getter;
import lombok.Setter;

/** params、fields 为 JSON 文本 */
@Getter
@Setter
@Protocol(id = 3085)
public class DataTemplateCreateRequest {

    private Long sourceId;
    private String name;
    private String query;
    private String params;
    private String fields;
}
