package com.oaixdog.adminbackground.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("project")
public class Project {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String appKey;
    private String name;
    private String description;
    private Integer status;
    /** 业务服上报节点信息的验签密钥，SecretCipher 加密保存，为空时首次需要时生成（NodeInfoReportService） */
    private String reportSecret;
    private Long createdAt;
    private Long updatedAt;
}
