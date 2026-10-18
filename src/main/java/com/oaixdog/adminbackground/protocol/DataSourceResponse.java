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
    private String type;
    private String serviceType;
    /** 按 serviceType 匹配到的业务类型名称，匹配不到为空串 */
    private String serviceTypeName = "";
    private String network;
    private String url;
    private boolean secretSet;
    private int timeoutMs;
    private String remark;
    private long templateCount;
    /** 按业务类型发现的数据源当前可查询的目标数，固定地址为 null */
    private Integer targetCount;
    private long createdAt;
    private long updatedAt;

    public static DataSourceResponse valueOf(DataSource source, long templateCount, Integer targetCount) {
        DataSourceResponse response = new DataSourceResponse();
        response.id = source.getId();
        response.name = source.getName();
        response.type = source.getType();
        response.serviceType = source.getServiceType();
        response.targetCount = targetCount;
        response.network = source.getNetwork();
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
