package com.oaixdog.adminbackground.controller;

import com.oaixdog.adminbackground.exception.BusinessException;
import com.oaixdog.adminbackground.exception.ErrorCode;
import com.oaixdog.adminbackground.protocol.ApiResponse;
import com.oaixdog.adminbackground.protocol.MenuCreateRequest;
import com.oaixdog.adminbackground.protocol.MenuListRequest;
import com.oaixdog.adminbackground.protocol.MenuRemoveRequest;
import com.oaixdog.adminbackground.protocol.MenuUpdateRequest;
import com.oaixdog.adminbackground.service.MenuService;
import com.oaixdog.adminbackground.service.OperationLogService;
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

import java.util.Map;

@Component
@RequiredArgsConstructor
public class MenuController {

    private static final Logger log = LoggerFactory.getLogger(MenuController.class);

    private final MenuService menuService;
    private final OperationLogService operationLogService;


    @PacketReceiver
    public void atMenuListRequest(Session session, MenuListRequest request, HttpAttachment attachment) {
        handle(session, attachment, "菜单列表", request, () -> Map.of("list", menuService.list(authorization(attachment))));
    }

    @PacketReceiver
    public void atMenuCreateRequest(Session session, MenuCreateRequest request, HttpAttachment attachment) {
        handle(session, attachment, "创建菜单", request, () -> menuService.create(authorization(attachment), request));
    }

    @PacketReceiver
    public void atMenuUpdateRequest(Session session, MenuUpdateRequest request, HttpAttachment attachment) {
        handle(session, attachment, "修改菜单", request, () -> menuService.update(authorization(attachment), request));
    }

    @PacketReceiver
    public void atMenuRemoveRequest(Session session, MenuRemoveRequest request, HttpAttachment attachment) {
        handle(session, attachment, "删除菜单", request, () -> {
            menuService.remove(authorization(attachment), request.getMenuId());
            return null;
        });
    }

    private void handle(Session session, HttpAttachment attachment, String action, Object request, Handler handler) {
        OperationLogService.Operation operation = operationLogService.start(attachment);
        try {
            if (request == null) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST);
            }
            Object data = handler.run();
            attachment.setHttpResponseStatus(HttpResponseStatus.OK);
            NetContext.getRouter().send(session, ApiResponse.success(data), attachment);
            operationLogService.finish(operation, request, 0);
        } catch (BusinessException exception) {
            log.warn("{}业务失败: {}", action, exception.getMessage());
            attachment.setHttpResponseStatus(HttpResponseStatus.OK);
            NetContext.getRouter().send(session, ApiResponse.businessError(exception.getCode(), exception.getMessage()), attachment);
            operationLogService.finish(operation, request, exception.getCode());
        }
    }

    private static String authorization(HttpAttachment attachment) {
        return attachment.getFullHttpRequest().headers().get(HttpHeaderNames.AUTHORIZATION);
    }

    private interface Handler {
        Object run();
    }
}
