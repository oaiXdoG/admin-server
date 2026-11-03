package com.oaixdog.adminbackground.service;

import com.oaixdog.adminbackground.exception.BusinessException;
import com.oaixdog.adminbackground.exception.ErrorCode;
import com.oaixdog.adminbackground.protocol.LogQueryResponse;
import com.oaixdog.protocol.log.EventCondition;
import com.oaixdog.protocol.log.EventQuery;
import com.oaixdog.protocol.log.EventQueryResult;
import com.zfoo.protocol.util.JsonUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 日志查询：事件定义和查询都转发给日志服务（zfoo RPC），字段、运算符、值的校验以日志服务为准。需要 log_query。
 */
@Service
@RequiredArgsConstructor
public class LogQueryService {

    private static final String MENU = "log_query";
    private static final int CONDITIONS_MAX_LENGTH = 16_384;
    private static final int CONDITIONS_MAX = 20;

    private final PermissionService permissionService;
    private final LogServiceLocator logServiceLocator;

    public LogQueryResponse query(String authorization, String event, String conditions, Integer offset, Integer limit) {
        permissionService.requireMenu(authorization, MENU);
        var query = new EventQuery();
        query.setEvent(event);
        query.setConditions(parseConditions(conditions));
        query.setOffset(offset == null ? 0 : offset);
        query.setLimit(limit == null ? 20 : limit);

        EventQueryResult result = logServiceLocator.ask(query, EventQueryResult.class, ErrorCode.LOG_QUERY_FAILED);
        if (result.getCode() != 1) {
            throw new BusinessException(ErrorCode.LOG_QUERY_FAILED, result.getMessage());
        }
        var response = new LogQueryResponse();
        response.setTotal(result.getTotal());
        response.setList(result.getRows().stream()
                .map(row -> JsonUtils.string2Map(row, String.class, Object.class))
                .toList());
        return response;
    }

    @SuppressWarnings("rawtypes")
    private static List<EventCondition> parseConditions(String conditions) {
        if (conditions == null || conditions.isBlank()) {
            return List.of();
        }
        if (conditions.length() > CONDITIONS_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.LOG_QUERY_CONDITION_INVALID);
        }
        List<Map> items;
        try {
            items = JsonUtils.string2List(conditions, Map.class);
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.LOG_QUERY_CONDITION_INVALID);
        }
        if (items == null || items.size() > CONDITIONS_MAX) {
            throw new BusinessException(ErrorCode.LOG_QUERY_CONDITION_INVALID);
        }
        List<EventCondition> result = new ArrayList<>();
        for (Map item : items) {
            if (item == null || !(item.get("key") instanceof String key) || !(item.get("op") instanceof String op)
                    || item.get("value") == null) {
                throw new BusinessException(ErrorCode.LOG_QUERY_CONDITION_INVALID);
            }
            var condition = new EventCondition();
            condition.setKey(key);
            condition.setOp(op);
            condition.setValue(String.valueOf(item.get("value")));
            result.add(condition);
        }
        return result;
    }
}
