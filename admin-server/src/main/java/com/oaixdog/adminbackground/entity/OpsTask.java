package com.oaixdog.adminbackground.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 一次后台运维操作（通过 SSH 执行）的过程和输出，见 OpsTaskService。谁做了什么记在操作日志 */
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
