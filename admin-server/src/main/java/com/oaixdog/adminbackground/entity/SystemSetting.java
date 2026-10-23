package com.oaixdog.adminbackground.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("system_setting")
public class SystemSetting {

    @TableId(type = IdType.INPUT)
    private String key;
    private String value;
    private Long updatedAt;
}
