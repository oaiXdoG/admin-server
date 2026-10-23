package com.oaixdog.adminbackground.service;

import java.util.regex.Pattern;

/**
 * 头像、Logo 等图片以 data URL（base64）直接存进数据库，这里只做格式和长度校验。
 */
final class ImageDataUrls {

    private static final Pattern DATA_URL = Pattern.compile("^data:image/(png|jpeg|webp|gif|x-icon|vnd\\.microsoft\\.icon|svg\\+xml);base64,[A-Za-z0-9+/=]+$");

    private ImageDataUrls() {
    }

    /** 空字符串表示不设置；否则必须是图片 data URL 且不超过 maxLength 个字符 */
    static boolean valid(String value, int maxLength) {
        return value == null || value.isEmpty() || (value.length() <= maxLength && DATA_URL.matcher(value).matches());
    }
}
