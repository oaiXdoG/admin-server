package com.oaixdog.adminbackground.protocol;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/** 每行包含 id、time（毫秒）、accountId、account、action、route、success、code、params */
@Getter
@Setter
public class OperationLogResponse {

    private long total;
    private List<Map<String, Object>> list;
}
