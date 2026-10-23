package com.oaixdog.adminbackground.protocol;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AccountCreateResponse {

    private long id;
    private String account;
    private String username;
    public static AccountCreateResponse valueOf(long id, String account, String username) {
        AccountCreateResponse response = new AccountCreateResponse();
        response.id = id;
        response.account = account;
        response.username = username;
        return response;
    }
}
