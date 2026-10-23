package com.oaixdog.adminbackground.service;

/** 密码规则：8～128 位，必须同时包含字母和数字。新建账号、管理员重置、本人修改都按这个规则校验 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 128;

    private PasswordPolicy() {
    }

    public static boolean valid(String password) {
        return password != null && password.length() >= MIN_LENGTH && password.length() <= MAX_LENGTH
                && password.chars().anyMatch(Character::isLetter) && password.chars().anyMatch(Character::isDigit);
    }
}
