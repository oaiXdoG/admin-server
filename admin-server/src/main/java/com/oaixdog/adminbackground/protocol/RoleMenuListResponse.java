package com.oaixdog.adminbackground.protocol;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class RoleMenuListResponse {

    private List<MenuResponse> menus;
    private List<Long> menuIds;

    public static RoleMenuListResponse valueOf(List<MenuResponse> menus, List<Long> menuIds) {
        RoleMenuListResponse response = new RoleMenuListResponse();
        response.menus = menus;
        response.menuIds = menuIds;
        return response;
    }
}
