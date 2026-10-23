package com.oaixdog.adminbackground.protocol;

import com.oaixdog.adminbackground.entity.Account;
import com.oaixdog.adminbackground.entity.Role;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class AccountResponse {

    private long id;
    private String account;
    private String username;
    private int status;
    private String remark;
    private Long lastLoginAt;
    private long createdAt;
    private List<RoleItem> roles;

    public static AccountResponse valueOf(Account account, List<Role> roles) {
        AccountResponse response = new AccountResponse();
        response.id = account.getId();
        response.account = account.getAccount();
        response.username = account.getUsername();
        response.status = account.getStatus() == null ? 1 : account.getStatus();
        response.remark = account.getRemark();
        response.lastLoginAt = account.getLastLoginAt();
        response.createdAt = account.getCreatedAt() == null ? 0 : account.getCreatedAt();
        response.roles = roles.stream().map(role -> new RoleItem(role.getId(), role.getName(), role.getCode())).toList();
        return response;
    }

    public record RoleItem(long id, String name, String code) {
    }
}
