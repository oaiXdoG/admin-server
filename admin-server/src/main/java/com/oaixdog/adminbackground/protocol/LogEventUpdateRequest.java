package com.oaixdog.adminbackground.protocol;

import com.zfoo.protocol.anno.Protocol;
import lombok.Getter;
import lombok.Setter;

/** 保存到日志服务的事件定义；name 是显示名称；fields 为字段数组 JSON 文本：[{key 上报字段名, field 存储字段名, type, required, name 显示名称, display 是否作为查询结果列}] */
@Getter
@Setter
@Protocol(id = 3092)
public class LogEventUpdateRequest {

    private String event;
    private String name;
    private String collection;
    private String fields;
}
