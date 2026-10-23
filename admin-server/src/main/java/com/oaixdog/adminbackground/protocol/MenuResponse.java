package com.oaixdog.adminbackground.protocol;

import com.oaixdog.adminbackground.entity.Menu;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class MenuResponse {

    private long id;
    private Long parentId;
    private int menuType;
    private String name;
    private String icon;
    private String routeName;
    private String routePath;
    private int status;
    private boolean hidden;
    private boolean builtIn;
    private int sort;

    public static MenuResponse valueOf(Menu menu) {
        MenuResponse response = new MenuResponse();
        response.id = menu.getId();
        response.parentId = menu.getParentId() == null ? 0L : menu.getParentId();
        response.menuType = menu.getMenuType();
        response.name = menu.getName();
        response.icon = menu.getIcon();
        response.routeName = menu.getRouteName();
        response.routePath = menu.getRoutePath();
        response.status = menu.getStatus();
        response.hidden = Boolean.TRUE.equals(menu.getHidden());
        response.builtIn = Boolean.TRUE.equals(menu.getBuiltIn());
        response.sort = menu.getSort() == null ? 0 : menu.getSort();
        return response;
    }

    public static List<MenuResponse> listOf(List<Menu> menus) {
        List<MenuResponse> list = new ArrayList<>();
        for (Menu menu : menus) {
            list.add(valueOf(menu));
        }
        return list;
    }
}
