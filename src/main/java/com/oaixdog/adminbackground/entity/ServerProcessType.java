package com.oaixdog.adminbackground.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 进程类型：项目内自定义的分类 */
@Getter
@Setter
@TableName("server_process_type")
public class ServerProcessType {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private String name;
    /** 业务类型编码，与业务服上报的 type 对应；为空表示不参与绑定 */
    private String code;
    private Long createdAt;
    private Long updatedAt;
}
