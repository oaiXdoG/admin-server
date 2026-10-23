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
        response.name = template.getName();
        response.query = template.getQuery();
        response.params = template.getParams();
        response.fields = template.getFields();
        response.createdAt = template.getCreatedAt();
        response.updatedAt = template.getUpdatedAt();
        return response;
    }
}
