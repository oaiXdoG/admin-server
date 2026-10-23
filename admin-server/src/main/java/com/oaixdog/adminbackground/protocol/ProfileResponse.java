package com.oaixdog.adminbackground.protocol;

import com.oaixdog.adminbackground.entity.Account;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class ProfileResponse {

    private long id;
    private String account;
    private String username;
    private String avatar;
    private String remark;
    private Long lastLoginAt;
    private long createdAt;
    private boolean superAdmin;
    private List<String> roles;

    public static ProfileResponse valueOf(Account account, boolean superAdmin, List<String> roles) {
        ProfileResponse response = new ProfileResponse();
        response.id = account.getId();
        response.account = account.getAccount();
        response.username = account.getUsername();
        response.avatar = account.getAvatar() == null ? "" : account.getAvatar();
        response.remark = account.getRemark();
        response.lastLoginAt = account.getLastLoginAt();
        response.createdAt = account.getCreatedAt() == null ? 0 : account.getCreatedAt();
        response.superAdmin = superAdmin;
        response.roles = roles;
        return response;
    }
}
