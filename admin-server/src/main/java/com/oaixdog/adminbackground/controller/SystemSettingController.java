package com.oaixdog.adminbackground.controller;

import com.oaixdog.adminbackground.exception.BusinessException;
import com.oaixdog.adminbackground.exception.ErrorCode;
import com.oaixdog.adminbackground.protocol.ApiResponse;
import com.oaixdog.adminbackground.protocol.SystemSettingGetRequest;
import com.oaixdog.adminbackground.protocol.SystemSettingUpdateRequest;
import com.oaixdog.adminbackground.service.OperationLogService;
import com.oaixdog.adminbackground.service.SystemSettingService;
import com.zfoo.net.NetContext;
import com.zfoo.net.anno.PacketReceiver;
import com.zfoo.net.router.attachment.HttpAttachment;
import com.zfoo.net.session.Session;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SystemSettingController {

    private final SystemSettingService systemSettingService;
    private final OperationLogService operationLogService;


    @PacketReceiver
    public void atSystemSettingGetRequest(Session session, SystemSettingGetRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> systemSettingService.get());
    }

    @PacketReceiver
    public void atSystemSettingUpdateRequest(Session session, SystemSettingUpdateRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> systemSettingService.update(auth(attachment), request.getAppName(), request.getLogo(), request.getFavicon(), request.getLoginTitle(), request.getLoginDescription(), request.getCopyright()));
    }

    private void handle(Session session, HttpAttachment attachment, Object request, Handler handler) {
        OperationLogService.Operation operation = operationLogService.start(attachment);
        try {
            if (request == null) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST);
            }
            attachment.setHttpResponseStatus(HttpResponseStatus.OK);
            NetContext.getRouter().send(session, ApiResponse.success(handler.run()), attachment);
            operationLogService.finish(operation, request, 0);
        } catch (BusinessException exception) {
            attachment.setHttpResponseStatus(HttpResponseStatus.OK);
            NetContext.getRouter().send(session, ApiResponse.businessError(exception.getCode(), exception.getMessage()), attachment);
            operationLogService.finish(operation, request, exception.getCode());
        }
    }

    private static String auth(HttpAttachment attachment) {
        return attachment.getFullHttpRequest().headers().get(HttpHeaderNames.AUTHORIZATION);
    }

    private interface Handler {
        Object run();
    }
}
