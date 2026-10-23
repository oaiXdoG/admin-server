package com.oaixdog.adminbackground.controller;

import com.oaixdog.adminbackground.exception.BusinessException;
import com.oaixdog.adminbackground.exception.ErrorCode;
import com.oaixdog.adminbackground.protocol.AccessMenuRequest;
import com.oaixdog.adminbackground.protocol.ApiResponse;
import com.oaixdog.adminbackground.service.PermissionService;
import com.zfoo.net.NetContext;
import com.zfoo.net.anno.PacketReceiver;
import com.zfoo.net.router.attachment.HttpAttachment;
import com.zfoo.net.session.Session;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AccessController {

    private static final Logger log = LoggerFactory.getLogger(AccessController.class);

    private final PermissionService permissionService;

    @PacketReceiver
    public void atAccessMenuRequest(Session session, AccessMenuRequest request, HttpAttachment attachment) {
        try {
            if (request == null) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST);
            }
            String authorization = attachment.getFullHttpRequest().headers().get(HttpHeaderNames.AUTHORIZATION);
            attachment.setHttpResponseStatus(HttpResponseStatus.OK);
            NetContext.getRouter().send(session, ApiResponse.success(permissionService.listMenus(authorization)), attachment);
        } catch (BusinessException exception) {
            log.warn("当前菜单业务失败: {}", exception.getMessage());
            attachment.setHttpResponseStatus(HttpResponseStatus.OK);
            NetContext.getRouter().send(session, ApiResponse.businessError(exception.getCode(), exception.getMessage()), attachment);
        }
    }
}
