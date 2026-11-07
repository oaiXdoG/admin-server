package com.oaixdog.adminbackground.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 运维任务：每次执行 Ansible（初始化、启停、发布、回滚、保存配置）的记录，见 OpsTaskService */
@Getter
@Setter
@TableName("ops_task")
public class OpsTask {

    public static final String RUNNING = "running";
    public static final String SUCCESS = "success";
    public static final String FAILED = "failed";

    @TableId(type = IdType.AUTO)
    private Long id;
    /** init、start、stop、restart、deploy、rollback、config */
    private String type;
    private String title;
    private Long hostId;
    private Long appId;
    private String account;
    private String status;
    private String output;
    private Long startedAt;
    private Long finishedAt;
}
