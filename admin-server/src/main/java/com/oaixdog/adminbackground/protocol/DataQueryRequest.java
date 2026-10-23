package com.oaixdog.adminbackground.protocol;

import com.zfoo.protocol.anno.Protocol;
import lombok.Getter;
import lombok.Setter;


/** params 为参数值 JSON 对象文本，按模板的参数定义校验和转换类型 */
@Getter
@Setter
@Protocol(id = 3088)
public class DataQueryRequest {

    private Long templateId;
    private String params;
    private Integer page;
    private Integer pageSize;
}
