package com.oaixdog.adminbackground.protocol;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/** 结果行以上报字段名为 key，按写入顺序倒序 */
@Getter
@Setter
public class LogQueryResponse {

    private long total;
    private List<Map<String, Object>> list;
}
