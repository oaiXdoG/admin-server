package com.oaixdog.adminbackground.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.oaixdog.adminbackground.entity.DataSource;
import com.oaixdog.adminbackground.entity.DataTemplate;
import com.oaixdog.adminbackground.exception.BusinessException;
import com.oaixdog.adminbackground.exception.ErrorCode;
import com.oaixdog.adminbackground.mapper.DataSourceMapper;
import com.oaixdog.adminbackground.mapper.DataTemplateMapper;
import com.oaixdog.adminbackground.protocol.DataTemplateResponse;
import com.zfoo.protocol.util.JsonUtils;
import com.zfoo.protocol.util.StringUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 数据查询模板：管理员选数据源、填查询名，定义参数和结果字段；使用者在数据查询页面只填参数。
 * 管理需要 data_template 下的按钮；数据查询页面读取模板只需要 data_query。
 */
@Service
@RequiredArgsConstructor
public class DataTemplateService {

    private static final String MENU = "data_template";
    private static final String QUERY_MENU = "data_query";
    private static final int NAME_MAX_LENGTH = 64;
    private static final int LABEL_MAX_LENGTH = 64;
    private static final int JSON_MAX_LENGTH = 65_536;
    private static final int PARAMS_MAX = 30;
    private static final int FIELDS_MAX = 100;
    private static final Pattern QUERY = Pattern.compile("^[A-Za-z][A-Za-z0-9_.-]{0,63}$");
    private static final Pattern KEY = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,63}$");
    private static final Pattern PATH = Pattern.compile("^[A-Za-z0-9_$-]{1,64}(\\.[A-Za-z0-9_$-]{1,64}){0,7}$");
    public static final Set<String> PARAM_TYPES = Set.of("string", "number", "boolean", "time", "select");
    private static final Set<String> FIELD_TYPES = Set.of("string", "number", "boolean", "time", "json", "jsonText");

    private final PermissionService permissionService;
    private final DataTemplateMapper dataTemplateMapper;
    private final DataSourceMapper dataSourceMapper;
    private final DataSourceService dataSourceService;

    /** 参数定义：select 类型的 options 是可选值 */
    public record Param(String name, String label, String type, boolean required, List<String> options) {
    }

    /** 模板管理页面和数据查询页面都要读取模板，有其中一个页面即可 */
    public List<DataTemplateResponse> list(String authorization) {
        permissionService.requireAnyMenu(authorization, MENU, QUERY_MENU);
        Map<Long, DataSource> sources = dataSourceMapper.selectList(Wrappers.<DataSource>lambdaQuery())
                .stream().collect(Collectors.toMap(DataSource::getId, Function.identity()));
        return dataTemplateMapper.selectList(Wrappers.<DataTemplate>lambdaQuery().orderByAsc(DataTemplate::getId))
                .stream().map(template -> DataTemplateResponse.valueOf(template, sources.get(template.getSourceId()))).toList();
    }

    public DataTemplateResponse create(String authorization, Long sourceId, String name, String query, String params, String fields) {
        permissionService.requireMenu(authorization, MENU + ":create");
        DataTemplate template = new DataTemplate();
        DataSource source = fill(template, sourceId, name, query, params, fields);
        long now = System.currentTimeMillis();
        template.setCreatedAt(now);
        template.setUpdatedAt(now);
        requireUniqueName(template);
        try {
            dataTemplateMapper.insert(template);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.DATA_TEMPLATE_NAME_EXISTS);
        }
        return DataTemplateResponse.valueOf(template, source);
    }

    public DataTemplateResponse update(String authorization, Long templateId, Long sourceId, String name, String query, String params, String fields) {
        permissionService.requireMenu(authorization, MENU + ":update");
        DataTemplate template = requireTemplate(templateId);
        DataSource source = fill(template, sourceId, name, query, params, fields);
        template.setUpdatedAt(System.currentTimeMillis());
        requireUniqueName(template);
        try {
            dataTemplateMapper.updateById(template);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.DATA_TEMPLATE_NAME_EXISTS);
        }
        return DataTemplateResponse.valueOf(template, source);
    }

    public void remove(String authorization, Long templateId) {
        permissionService.requireMenu(authorization, MENU + ":remove");
        dataTemplateMapper.deleteById(requireTemplate(templateId).getId());
    }

    public DataTemplate requireTemplate(Long templateId) {
        DataTemplate template = templateId == null ? null : dataTemplateMapper.selectById(templateId);
        if (template == null) {
            throw new BusinessException(ErrorCode.DATA_TEMPLATE_NOT_FOUND);
        }
        return template;
    }

    /** 解析并校验参数定义：name 唯一，类型合法，select 至少一个选项 */
    @SuppressWarnings("unchecked")
    public static List<Param> parseParams(String params) {
        List<Map> items = parseArray(params, PARAMS_MAX, true);
        List<Param> result = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Map<String, Object> item : items) {
            String name = text(item.get("name"));
            String label = text(item.get("label"));
            String type = text(item.get("type"));
            List<String> options = new ArrayList<>();
            if (item.get("options") instanceof List<?> list) {
                for (Object option : list) {
                    Object value = option instanceof Map<?, ?> map ? map.get("value") : option;
                    if (value != null) {
                        options.add(String.valueOf(value));
                    }
                }
            }
            if (!KEY.matcher(name).matches() || !names.add(name) || label.isEmpty() || label.length() > LABEL_MAX_LENGTH
                    || !PARAM_TYPES.contains(type) || ("select".equals(type) && options.isEmpty())) {
                throw new BusinessException(ErrorCode.DATA_TEMPLATE_INVALID);
            }
            result.add(new Param(name, label, type, Boolean.TRUE.equals(item.get("required")), options));
        }
        return result;
    }

    /** 校验结果字段：key 唯一，source 是结果行里的字段路径（点号分隔） */
    @SuppressWarnings("unchecked")
    private static void validateFields(String fields) {
        Set<String> keys = new HashSet<>();
        for (Map<String, Object> item : parseArray(fields, FIELDS_MAX, false)) {
            String key = text(item.get("key"));
            String label = text(item.get("label"));
            if (!KEY.matcher(key).matches() || !keys.add(key) || label.isEmpty() || label.length() > LABEL_MAX_LENGTH
                    || !PATH.matcher(text(item.get("source"))).matches() || !FIELD_TYPES.contains(text(item.get("type")))) {
                throw new BusinessException(ErrorCode.DATA_TEMPLATE_INVALID);
            }
        }
    }

    @SuppressWarnings("rawtypes")
    private static List<Map> parseArray(String json, int max, boolean allowEmpty) {
        if (json == null || json.length() > JSON_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.DATA_TEMPLATE_INVALID);
        }
        List<Map> items;
        try {
            items = JsonUtils.string2List(json, Map.class);
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.DATA_TEMPLATE_INVALID);
        }
        if (items == null || items.size() > max || (!allowEmpty && items.isEmpty()) || items.stream().anyMatch(Objects::isNull)) {
            throw new BusinessException(ErrorCode.DATA_TEMPLATE_INVALID);
        }
        return items;
    }

    private static String text(Object value) {
        return value instanceof String string ? string.trim() : "";
    }

    private DataSource fill(DataTemplate template, Long sourceId, String name, String query, String params, String fields) {
        String templateName = name == null ? "" : name.trim();
        String queryName = query == null ? "" : query.trim();
        if (templateName.isEmpty() || templateName.length() > NAME_MAX_LENGTH || !QUERY.matcher(queryName).matches()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        DataSource source = dataSourceService.requireSource(sourceId);
        parseParams(params);
        validateFields(fields);
        template.setSourceId(source.getId());
        template.setName(templateName);
        template.setQuery(queryName);
        template.setParams(params);
        template.setFields(fields);
        return source;
    }

    private void requireUniqueName(DataTemplate template) {
        if (dataTemplateMapper.selectCount(Wrappers.<DataTemplate>lambdaQuery()
                .eq(DataTemplate::getName, template.getName()).ne(template.getId() != null, DataTemplate::getId, template.getId())) > 0) {
            throw new BusinessException(ErrorCode.DATA_TEMPLATE_NAME_EXISTS);
        }
    }
}
