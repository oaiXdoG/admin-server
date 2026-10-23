package com.oaixdog.adminbackground.protocol;

import com.oaixdog.adminbackground.entity.Role;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RoleResponse {

    private long id;
    private String name;
    private String code;
    private String description;
    private int status;
    private boolean builtIn;
    private long createdAt;

    public static RoleResponse valueOf(Role role) {
        RoleResponse response = new RoleResponse();
        response.id = role.getId();
        response.name = role.getName();
        response.code = role.getCode();
        response.description = role.getDescription();
        response.status = role.getStatus() == null ? 1 : role.getStatus();
        response.builtIn = Boolean.TRUE.equals(role.getBuiltIn());
        response.createdAt = role.getCreatedAt() == null ? 0 : role.getCreatedAt();
        return response;
    }
}
