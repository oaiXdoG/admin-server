package com.oaixdog.adminbackground.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("menu")
public class Menu {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long parentId;
    private Integer menuType;
    private String name;
    private String icon;
    private String routeName;
    private String routePath;
    private Integer status;
    private Boolean hidden;
    private Boolean builtIn;
    @TableField("sort_order")
    private Integer sort;
    private Long createdAt;
    private Long updatedAt;
}
