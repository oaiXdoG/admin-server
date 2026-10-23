package com.oaixdog.adminbackground.protocol;

import com.zfoo.protocol.anno.Protocol;
import lombok.Getter;
import lombok.Setter;

/** 读取数据源提供的查询清单 */
@Getter
@Setter
@Protocol(id = 3083)
public class DataSourceQueriesRequest {

    private Long sourceId;
}
