package com.oaixdog.adminbackground.protocol;

import com.zfoo.protocol.anno.Protocol;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** 与 DataQueryRequest 相同但不分页，最多导出 5 万行 */
@Getter
@Setter
@Protocol(id = 3089)
public class DataQueryExportRequest {

    private Long projectId;
    private Long templateId;
    /** 按业务类型发现的数据源：要查询的目标 key（区号或节点标识） */
    private List<String> targets;
    private String params;
}
