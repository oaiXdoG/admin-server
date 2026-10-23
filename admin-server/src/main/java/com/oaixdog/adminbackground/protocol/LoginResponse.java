package com.oaixdog.adminbackground.protocol;

import com.zfoo.protocol.anno.Protocol;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Protocol(id = 3001)
public class LoginResponse {

    private String token;
    private long id;
    private String account;
    private String username;
    public static LoginResponse valueOf(String token, long id, String account, String username) {
        LoginResponse response = new LoginResponse();
        response.token = token;
        response.id = id;
        response.account = account;
        response.username = username;
        return response;
    }
}
