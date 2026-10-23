package com.oaixdog.adminbackground.protocol;

import com.zfoo.protocol.anno.Protocol;
import lombok.Getter;
import lombok.Setter;

/** 时间为毫秒时间戳，范围为 [startTime, endTime)；success 为空表示不限 */
@Getter
@Setter
@Protocol(id = 3067)
public class OperationLogListRequest {

    private Long startTime;
    private Long endTime;
    private String account;
    private String action;
    private Boolean success;
    private Integer page;
    private Integer pageSize;
}
