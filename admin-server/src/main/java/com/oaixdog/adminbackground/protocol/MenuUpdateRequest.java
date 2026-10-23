package com.oaixdog.adminbackground.protocol;

import com.zfoo.protocol.anno.Protocol;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Protocol(id = 3008)
public class MenuUpdateRequest {

    private Long menuId;
    private Long parentId;
    private Integer menuType;
    private String name;
    private String icon;
    private String routeName;
    private String routePath;
    private Integer status;
    private Boolean hidden;
    private Integer sort;
}
