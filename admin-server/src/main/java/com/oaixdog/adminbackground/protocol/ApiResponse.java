package com.oaixdog.adminbackground.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ApiResponse {

    private int code;
    private String message;
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private Object data;

    public static ApiResponse success(Object data) {
        return valueOf(0, "success", data);
    }

    public static ApiResponse businessError(int code, String message) {
        return valueOf(code, message, null);
    }

    public static ApiResponse systemError(int code, String message) {
        return valueOf(code, message, null);
    }

    public static ApiResponse valueOf(int code, String message, Object data) {
        ApiResponse response = new ApiResponse();
        response.code = code;
        response.message = message;
        response.data = data;
        return response;
    }
}
