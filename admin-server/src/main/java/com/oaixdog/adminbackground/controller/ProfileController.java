package com.oaixdog.adminbackground.controller;

import com.oaixdog.adminbackground.exception.BusinessException;
import com.oaixdog.adminbackground.exception.ErrorCode;
import com.oaixdog.adminbackground.protocol.ApiResponse;
import com.oaixdog.adminbackground.protocol.ProfileGetRequest;
import com.oaixdog.adminbackground.protocol.ProfilePasswordRequest;
import com.oaixdog.adminbackground.protocol.ProfileUpdateRequest;
import com.oaixdog.adminbackground.service.OperationLogService;
import com.oaixdog.adminbackground.service.ProfileService;
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
public class ProfileController {

    private final ProfileService profileService;
    private final OperationLogService operationLogService;


    @PacketReceiver
    public void atProfileGetRequest(Session session, ProfileGetRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> profileService.get(auth(attachment)));
    }

    @PacketReceiver
    public void atProfileUpdateRequest(Session session, ProfileUpdateRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> profileService.update(auth(attachment), request.getUsername(), request.getAvatar(), request.getRemark()));
    }

    @PacketReceiver
    public void atProfilePasswordRequest(Session session, ProfilePasswordRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> {
            profileService.changePassword(auth(attachment), request.getOldPassword(), request.getNewPassword());
            return null;
        });
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
