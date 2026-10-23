package com.oaixdog.adminbackground.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 数据查询模板：数据源上的一个查询，params 是参数定义 JSON，fields 是结果字段映射 JSON */
@Getter
@Setter
@TableName("data_template")
public class DataTemplate {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sourceId;
    private String name;
    private String query;
    private String params;
    private String fields;
    private Long createdAt;
    private Long updatedAt;
}
