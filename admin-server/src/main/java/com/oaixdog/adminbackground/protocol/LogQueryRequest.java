package com.oaixdog.adminbackground.protocol;

import com.zfoo.protocol.anno.Protocol;
import lombok.Getter;
import lombok.Setter;

/** event 为日志服务里的事件名；conditions 为条件数组 JSON 文本：[{key, op, value}]，value 为文本，date 字段填毫秒 */
@Getter
@Setter
@Protocol(id = 3094)
public class LogQueryRequest {

    private String event;
    private String conditions;
    private Integer offset;
    private Integer limit;
}
