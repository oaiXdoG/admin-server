package com.oaixdog.adminbackground.service;

import com.oaixdog.adminbackground.exception.BusinessException;
import com.oaixdog.adminbackground.exception.ErrorCode;
import com.oaixdog.protocol.log.EventDefinition;
import com.oaixdog.protocol.log.EventDefinitionList;
import com.oaixdog.protocol.log.EventDefinitionListResult;
import com.oaixdog.protocol.log.EventDefinitionSave;
import com.oaixdog.protocol.log.EventField;
import com.zfoo.net.packet.common.Message;
import com.zfoo.protocol.util.JsonUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 日志事件：事件定义保存在日志服务里，这里只转发读取和保存，命名、类型、只增不改等校验以日志服务为准。
 * 管理需要 log_event 下的按钮；日志查询页面读取事件只需要 log_query。日志服务没有删除定义的协议。
 */
@Service
@RequiredArgsConstructor
public class LogEventService {

    private static final String MENU = "log_event";
    private static final String QUERY_MENU = "log_query";
    private static final int JSON_MAX_LENGTH = 65_536;
    private static final int FIELDS_MAX = 100;

    private final PermissionService permissionService;
    private final LogServiceLocator logServiceLocator;

    /** 事件管理页面和日志查询页面都要读取事件，有其中一个页面即可 */
    public List<EventDefinition> list(String authorization) {
        permissionService.requireAnyMenu(authorization, MENU, QUERY_MENU);
        return definitions();
    }

    public void create(String authorization, String event, String name, String collection, String fields) {
        permissionService.requireMenu(authorization, MENU + ":create");
        EventDefinition definition = toDefinition(event, name, collection, fields);
        if (find(definition.getEvent()) != null) {
            throw new BusinessException(ErrorCode.LOG_EVENT_EXISTS);
        }
        save(definition);
    }

    public void update(String authorization, String event, String name, String collection, String fields) {
        permissionService.requireMenu(authorization, MENU + ":update");
        EventDefinition definition = toDefinition(event, name, collection, fields);
        if (find(definition.getEvent()) == null) {
            throw new BusinessException(ErrorCode.LOG_EVENT_NOT_FOUND);
        }
        save(definition);
    }

    private List<EventDefinition> definitions() {
        EventDefinitionListResult result = logServiceLocator.ask(new EventDefinitionList(), EventDefinitionListResult.class,
                ErrorCode.LOG_QUERY_FAILED);
        if (result.getCode() != 1) {
            throw new BusinessException(ErrorCode.LOG_QUERY_FAILED, result.getMessage());
        }
        return result.getDefinitions();
    }

    private EventDefinition find(String event) {
        return definitions().stream().filter(it -> it.getEvent().equals(event)).findFirst().orElse(null);
    }

    private void save(EventDefinition definition) {
        var save = new EventDefinitionSave();
        save.setDefinition(definition);
        Message answer = logServiceLocator.ask(save, Message.class, ErrorCode.LOG_EVENT_INVALID);
        if (answer.getCode() != 1) {
            throw new BusinessException(ErrorCode.LOG_EVENT_INVALID, answer.getMessage());
        }
    }

    /** 只解析 JSON 结构，字段内容交给日志服务校验 */
    @SuppressWarnings("rawtypes")
    private static EventDefinition toDefinition(String event, String name, String collection, String fields) {
        if (fields == null || fields.length() > JSON_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.LOG_EVENT_INVALID);
        }
        List<Map> items;
        try {
            items = JsonUtils.string2List(fields, Map.class);
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.LOG_EVENT_INVALID);
        }
        if (items == null || items.size() > FIELDS_MAX) {
            throw new BusinessException(ErrorCode.LOG_EVENT_INVALID);
        }
        List<EventField> result = new ArrayList<>();
        for (Map item : items) {
            if (item == null) {
                throw new BusinessException(ErrorCode.LOG_EVENT_INVALID);
            }
            var field = new EventField();
            field.setKey(text(item.get("key")));
            field.setField(text(item.get("field")));
            field.setType(text(item.get("type")));
            field.setRequired(Boolean.TRUE.equals(item.get("required")));
            field.setName(text(item.get("name")));
            field.setDisplay(Boolean.TRUE.equals(item.get("display")));
            result.add(field);
        }
        var definition = new EventDefinition();
        definition.setEvent(text(event));
        definition.setCollection(text(collection));
        definition.setName(text(name));
        definition.setFields(result);
        return definition;
    }

    private static String text(Object value) {
        return value instanceof String string ? string.trim() : "";
    }
}
