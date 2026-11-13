package com.oaixdog.adminbackground.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 运维中心的程序：部署在某台机器（ip、sshPort）的某个目录。JVM 参数在程序自己的 conf/jvm.options，由程序的 bin/app.sh 读取 */
@Getter
@Setter
@TableName("ops_app")
public class OpsApp {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String ip;
    private Integer sshPort;
    private String dir;
    private String remark;
    /** 最近一次发布成功的时间，0 表示还没有发布过 */
    private Long deployedAt;
    private Long createdAt;
    private Long updatedAt;
}
