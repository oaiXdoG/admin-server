package com.oaixdog.adminbackground.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 运维中心的主机：在后台手动添加，管理后台用 Ansible 通过 SSH（ip:sshPort）管理；hostname 为读到的系统主机名，只用于展示 */
@Getter
@Setter
@TableName("ops_host")
public class OpsHost {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String ip;
    private Integer sshPort;
    private String hostname;
    private String remark;
    /** 「初始化」成功的时间，0 表示还没有初始化 */
    private Long initializedAt;
    private Long lastSeenAt;
    private Long createdAt;
    private Long updatedAt;
}
