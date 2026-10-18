package com.oaixdog.adminbackground.protocol;

import com.zfoo.protocol.anno.Protocol;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** params 为参数值 JSON 对象文本，按模板的参数定义校验和转换类型 */
@Getter
@Setter
@Protocol(id = 3088)
public class DataQueryRequest {

    private Long projectId;
    private Long templateId;
    /** 按业务类型发现的数据源：要查询的目标 key（区号或节点标识） */
    private List<String> targets;
    private String params;
    private Integer page;
    private Integer pageSize;
}
