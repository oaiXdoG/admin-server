package com.oaixdog.adminbackground.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("role_menu")
public class RoleMenu {

    private Long roleId;
    private Long menuId;
}
