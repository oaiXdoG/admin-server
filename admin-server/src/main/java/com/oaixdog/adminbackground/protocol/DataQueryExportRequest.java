package com.oaixdog.adminbackground.protocol;

import com.zfoo.protocol.anno.Protocol;
import lombok.Getter;
import lombok.Setter;


/** 与 DataQueryRequest 相同但不分页，最多导出 5 万行 */
@Getter
@Setter
@Protocol(id = 3089)
public class DataQueryExportRequest {

    private Long templateId;
    private String params;
}
