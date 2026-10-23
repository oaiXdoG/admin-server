package com.oaixdog.adminbackground.protocol;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/** 全局品牌信息，未设置的字段为空字符串，系统名称默认为 admin-front */
@Getter
@Setter
public class SystemSettingResponse {

    public static final String DEFAULT_APP_NAME = "admin-front";

    private String appName;
    private String logo;
    private String favicon;
    private String loginTitle;
    private String loginDescription;
    private String copyright;

    public static SystemSettingResponse valueOf(Map<String, String> values) {
        SystemSettingResponse response = new SystemSettingResponse();
        String appName = values.getOrDefault("appName", "");
        response.appName = appName.isBlank() ? DEFAULT_APP_NAME : appName;
        response.logo = values.getOrDefault("logo", "");
        response.favicon = values.getOrDefault("favicon", "");
        response.loginTitle = values.getOrDefault("loginTitle", "");
        response.loginDescription = values.getOrDefault("loginDescription", "");
        response.copyright = values.getOrDefault("copyright", "");
        return response;
    }
}
