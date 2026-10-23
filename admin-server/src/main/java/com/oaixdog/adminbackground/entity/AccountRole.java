package com.oaixdog.adminbackground.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("account_role")
public class AccountRole {

    private Long accountId;
    private Long roleId;
}
