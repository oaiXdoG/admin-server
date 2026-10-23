package com.oaixdog.adminbackground.protocol;

import com.oaixdog.adminbackground.entity.DataSource;
import lombok.Getter;
import lombok.Setter;

/** 不返回密钥，只返回是否已设置 */
@Getter
@Setter
public class DataSourceResponse {

    private long id;
    private String name;
    private String url;
    private boolean secretSet;
    private int timeoutMs;
    private String remark;
    private long templateCount;
    private long createdAt;
    private long updatedAt;

    public static DataSourceResponse valueOf(DataSource source, long templateCount) {
        DataSourceResponse response = new DataSourceResponse();
        response.id = source.getId();
        response.name = source.getName();
        response.url = source.getUrl();
        response.secretSet = source.getSecret() != null && !source.getSecret().isBlank();
        response.timeoutMs = source.getTimeoutMs();
        response.remark = source.getRemark();
        response.templateCount = templateCount;
        response.createdAt = source.getCreatedAt();
        response.updatedAt = source.getUpdatedAt();
        return response;
    }
}
