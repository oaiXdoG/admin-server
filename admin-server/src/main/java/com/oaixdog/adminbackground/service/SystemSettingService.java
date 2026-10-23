package com.oaixdog.adminbackground.service;

import com.oaixdog.adminbackground.entity.SystemSetting;
import com.oaixdog.adminbackground.exception.BusinessException;
import com.oaixdog.adminbackground.exception.ErrorCode;
import com.oaixdog.adminbackground.mapper.SystemSettingMapper;
import com.oaixdog.adminbackground.protocol.SystemSettingResponse;
import com.zfoo.protocol.util.StringUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 系统设置：所有人看到的品牌信息。读取不需要登录（登录页要用），修改需要按钮权限 manage_setting:update。
 */
@Service
@RequiredArgsConstructor
public class SystemSettingService {

    private static final int APP_NAME_MAX_LENGTH = 32;
    private static final int TEXT_MAX_LENGTH = 128;
    private static final int LOGO_MAX_LENGTH = 280_000;
    private static final int FAVICON_MAX_LENGTH = 100_000;

    private final PermissionService permissionService;
    private final SystemSettingMapper systemSettingMapper;

    public SystemSettingResponse get() {
        Map<String, String> values = systemSettingMapper.selectList(null).stream()
                .collect(Collectors.toMap(SystemSetting::getKey, SystemSetting::getValue));
        return SystemSettingResponse.valueOf(values);
    }

    @Transactional
    public SystemSettingResponse update(String authorization, String appName, String logo, String favicon,
                                        String loginTitle, String loginDescription, String copyright) {
        permissionService.requireMenu(authorization, "manage_setting:update");
        Map<String, String> values = new LinkedHashMap<>();
        values.put("appName", appName == null ? "" : appName.trim());
        values.put("loginTitle", loginTitle == null ? "" : loginTitle.trim());
        values.put("loginDescription", loginDescription == null ? "" : loginDescription.trim());
        values.put("copyright", copyright == null ? "" : copyright.trim());
        if (StringUtils.isBlank(values.get("appName")) || values.get("appName").length() > APP_NAME_MAX_LENGTH
                || values.get("loginTitle").length() > TEXT_MAX_LENGTH || values.get("loginDescription").length() > TEXT_MAX_LENGTH
                || values.get("copyright").length() > TEXT_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        if (!ImageDataUrls.valid(logo, LOGO_MAX_LENGTH) || !ImageDataUrls.valid(favicon, FAVICON_MAX_LENGTH)) {
            throw new BusinessException(ErrorCode.IMAGE_INVALID);
        }
        values.put("logo", logo == null ? "" : logo);
        values.put("favicon", favicon == null ? "" : favicon);
        long now = System.currentTimeMillis();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            SystemSetting setting = new SystemSetting();
            setting.setKey(entry.getKey());
            setting.setValue(entry.getValue());
            setting.setUpdatedAt(now);
            if (systemSettingMapper.updateById(setting) == 0) {
                systemSettingMapper.insert(setting);
            }
        }
        return get();
    }
}
