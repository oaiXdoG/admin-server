package com.oaixdog.adminbackground.controller;

import com.oaixdog.adminbackground.exception.BusinessException;
import com.oaixdog.adminbackground.exception.ErrorCode;
import com.oaixdog.adminbackground.protocol.AccountRoleOptionsRequest;
import com.oaixdog.adminbackground.protocol.ApiResponse;
import com.oaixdog.adminbackground.protocol.RoleCreateRequest;
import com.oaixdog.adminbackground.protocol.RoleListRequest;
import com.oaixdog.adminbackground.protocol.RoleMenuListRequest;
import com.oaixdog.adminbackground.protocol.RoleMenuUpdateRequest;
import com.oaixdog.adminbackground.protocol.RoleRemoveRequest;
import com.oaixdog.adminbackground.protocol.RoleUpdateRequest;
import com.oaixdog.adminbackground.service.OperationLogService;
import com.oaixdog.adminbackground.service.RoleService;
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
public class RoleController {

    private final RoleService roleService;
    private final OperationLogService operationLogService;


    @PacketReceiver
    public void atRoleListRequest(Session session, RoleListRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> roleService.list(auth(attachment)));
    }

    @PacketReceiver
    public void atRoleCreateRequest(Session session, RoleCreateRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> roleService.create(auth(attachment), request.getName(), request.getCode(), request.getDescription(), request.getStatus()));
    }

    @PacketReceiver
    public void atRoleUpdateRequest(Session session, RoleUpdateRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> roleService.update(auth(attachment), request.getRoleId(), request.getName(), request.getCode(), request.getDescription(), request.getStatus()));
    }

    @PacketReceiver
    public void atRoleRemoveRequest(Session session, RoleRemoveRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> {
            roleService.remove(auth(attachment), request.getRoleId());
            return null;
        });
    }

    @PacketReceiver
    public void atRoleMenuListRequest(Session session, RoleMenuListRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> roleService.listMenus(auth(attachment), request.getRoleId()));
    }

    @PacketReceiver
    public void atRoleMenuUpdateRequest(Session session, RoleMenuUpdateRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> {
            roleService.updateMenus(auth(attachment), request.getRoleId(), request.getMenuIds());
            return null;
        });
    }

    @PacketReceiver
    public void atAccountRoleOptionsRequest(Session session, AccountRoleOptionsRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> roleService.options(auth(attachment)));
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
