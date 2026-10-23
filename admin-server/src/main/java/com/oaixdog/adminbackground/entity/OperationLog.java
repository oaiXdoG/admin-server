package com.oaixdog.adminbackground.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 后台操作日志：登录、退出和所有写操作；params 为脱敏后的请求参数 JSON */
@Getter
@Setter
@TableName("operation_log")
public class OperationLog {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long accountId;
    private String account;
    private String route;
    private String action;
    private Boolean success;
    private Integer code;
    private String params;
    private Long createdAt;
}
