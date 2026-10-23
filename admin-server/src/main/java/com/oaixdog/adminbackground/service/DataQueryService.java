package com.oaixdog.adminbackground.service;

import com.oaixdog.adminbackground.entity.Account;
import com.oaixdog.adminbackground.entity.DataSource;
import com.oaixdog.adminbackground.entity.DataTemplate;
import com.oaixdog.adminbackground.exception.BusinessException;
import com.oaixdog.adminbackground.exception.ErrorCode;
import com.oaixdog.adminbackground.protocol.DataQueryResponse;
import com.zfoo.protocol.util.JsonUtils;
import com.zfoo.protocol.util.StringUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据查询：按模板调用业务服务器的查询接口。参数按模板定义校验并转换类型，模板里没有定义的参数不会发给业务服务器；
 * 结果行原样返回，由页面按模板的结果字段展示。查询需要 data_query，导出需要 data_query:export。
 */
@Service
@RequiredArgsConstructor
public class DataQueryService {

    private static final String MENU = "data_query";
    private static final int PAGE_SIZE_MAX = 200;
    private static final int EXPORT_MAX = 50_000;
    private static final int EXPORT_PAGE_SIZE = 1_000;
    private static final int PARAMS_MAX_LENGTH = 16_384;
    private static final int TEXT_MAX_LENGTH = 512;

    private final PermissionService permissionService;
    private final DataTemplateService dataTemplateService;
    private final DataSourceService dataSourceService;
    private final BusinessClient businessClient;

    private record Call(BusinessClient.Target target, String query, Map<String, Object> params) {
    }

    public DataQueryResponse query(String authorization, Long templateId, String params, Integer page, Integer pageSize) {
        Account operator = permissionService.requireMenu(authorization, MENU);
        int pageNumber = page == null ? 1 : page;
        int size = pageSize == null ? 20 : pageSize;
        if (pageNumber < 1 || size < 1 || size > PAGE_SIZE_MAX) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        return fetch(prepare(operator, templateId, params), pageNumber, size);
    }

    /** 按页依次取，最多 5 万行；业务服务器返回空页时停止 */
    public DataQueryResponse export(String authorization, Long templateId, String params) {
        permissionService.requireMenu(authorization, MENU + ":export");
        Account operator = permissionService.requireMenu(authorization, MENU);
        Call call = prepare(operator, templateId, params);
        List<Map<String, Object>> rows = new ArrayList<>();
        long total = 0;
        for (int page = 1; rows.size() < EXPORT_MAX; page++) {
            DataQueryResponse response = fetch(call, page, EXPORT_PAGE_SIZE);
            rows.addAll(response.getList());
            total = response.getTotal();
            if (response.getList().isEmpty() || rows.size() >= response.getTotal()) {
                break;
            }
        }
        DataQueryResponse response = new DataQueryResponse();
        response.setTotal(total);
        response.setList(rows.size() > EXPORT_MAX ? rows.subList(0, EXPORT_MAX) : rows);
        return response;
    }

    private Call prepare(Account operator, Long templateId, String params) {
        DataTemplate template = dataTemplateService.requireTemplate(templateId);
        DataSource source = dataSourceService.requireSource(template.getSourceId());
        Map<String, Object> values = convert(DataTemplateService.parseParams(template.getParams()), params);
        return new Call(dataSourceService.target(operator, source), template.getQuery(), values);
    }

    @SuppressWarnings("unchecked")
    private DataQueryResponse fetch(Call call, int page, int pageSize) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", call.query());
        body.put("params", call.params());
        body.put("page", page);
        body.put("pageSize", pageSize);
        Map<String, Object> data = businessClient.call(call.target(), DataSourceService.QUERY_PATH, body);
        List<Map<String, Object>> list = new ArrayList<>();
        if (data.get("list") instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof Map<?, ?> row) {
                    list.add((Map<String, Object>) row);
                }
            }
        }
        DataQueryResponse response = new DataQueryResponse();
        response.setTotal(data.get("total") instanceof Number number ? number.longValue() : list.size());
        response.setList(list);
        return response;
    }

    /** 按参数定义转换：必填检查；数字、布尔、时间（毫秒）转为对应类型；下拉只能是定义的选项；空值不传 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> convert(List<DataTemplateService.Param> definitions, String params) {
        if (params != null && params.length() > PARAMS_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.DATA_QUERY_PARAM_INVALID);
        }
        Map<String, Object> input;
        try {
            input = StringUtils.isBlank(params) ? Map.of() : JsonUtils.string2Object(params, Map.class);
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.DATA_QUERY_PARAM_INVALID);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (DataTemplateService.Param param : definitions) {
            Object raw = input.get(param.name());
            String text = raw == null ? "" : String.valueOf(raw).trim();
            if (text.isEmpty()) {
                if (param.required()) {
                    throw new BusinessException(ErrorCode.DATA_QUERY_PARAM_INVALID, "请填写「" + param.label() + "」");
                }
                continue;
            }
            try {
                Object value = switch (param.type()) {
                    case "number" -> raw instanceof Number number ? number : Double.parseDouble(text);
                    case "time" -> raw instanceof Number number ? number.longValue() : Long.parseLong(text);
                    case "boolean" -> {
                        if (!"true".equals(text) && !"false".equals(text)) {
                            throw new NumberFormatException();
                        }
                        yield Boolean.parseBoolean(text);
                    }
                    case "select" -> {
                        if (!param.options().contains(text)) {
                            throw new NumberFormatException();
                        }
                        yield text;
                    }
                    default -> {
                        if (text.length() > TEXT_MAX_LENGTH) {
                            throw new NumberFormatException();
                        }
                        yield text;
                    }
                };
                result.put(param.name(), value);
            } catch (NumberFormatException exception) {
                throw new BusinessException(ErrorCode.DATA_QUERY_PARAM_INVALID, "「" + param.label() + "」的值不合法");
            }
        }
        return result;
    }
}
