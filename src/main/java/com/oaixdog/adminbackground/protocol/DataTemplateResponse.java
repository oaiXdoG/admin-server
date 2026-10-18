package com.oaixdog.adminbackground.protocol;

import com.oaixdog.adminbackground.entity.DataSource;
import com.oaixdog.adminbackground.entity.DataTemplate;
import lombok.Getter;
import lombok.Setter;

/** params、fields 原样返回保存时的 JSON 文本 */
@Getter
@Setter
public class DataTemplateResponse {

    private long id;
    private long sourceId;
    private String sourceName;
    /** 数据源类型：http 固定地址 / discover 按业务类型发现（查询页据此显示区服或节点选择） */
    private String sourceType;
    /** 数据源的业务类型编码：logic 选区服，其他选节点；http 为空串 */
    private String sourceServiceType;
    private String name;
    private String query;
    private String params;
    private String fields;
    private long createdAt;
    private long updatedAt;

    public static DataTemplateResponse valueOf(DataTemplate template, DataSource source) {
        DataTemplateResponse response = new DataTemplateResponse();
        response.id = template.getId();
        response.sourceId = template.getSourceId();
        response.sourceName = source == null ? null : source.getName();
        response.sourceType = source == null ? null : source.getType();
        response.sourceServiceType = source == null ? null : source.getServiceType();
        response.name = template.getName();
        response.query = template.getQuery();
        response.params = template.getParams();
        response.fields = template.getFields();
        response.createdAt = template.getCreatedAt();
        response.updatedAt = template.getUpdatedAt();
        return response;
    }
}
