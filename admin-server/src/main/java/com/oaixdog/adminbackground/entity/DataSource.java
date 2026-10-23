package com.oaixdog.adminbackground.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 数据源：业务服务器按约定提供的查询接口，secret 为加密保存的签名密钥 */
@Getter
@Setter
@TableName("data_source")
public class DataSource {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String url;
    private String secret;
    private Integer timeoutMs;
    private String remark;
    private Long createdAt;
    private Long updatedAt;
}
