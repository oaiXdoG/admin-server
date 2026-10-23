package com.oaixdog.adminbackground.entity;

/** 菜单、角色、账号、项目共用的状态值：项目的停用即已关闭 */
public final class Status {

    public static final int ENABLED = 1;
    public static final int DISABLED = 2;

    private Status() {
    }

    public static boolean valid(Integer status) {
        return status != null && (status == ENABLED || status == DISABLED);
    }
}
