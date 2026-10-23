package com.oaixdog.adminbackground.controller;

import com.oaixdog.adminbackground.exception.BusinessException;
import com.oaixdog.adminbackground.exception.ErrorCode;
import com.oaixdog.adminbackground.protocol.ApiResponse;
import com.oaixdog.adminbackground.protocol.LoginRequest;
import com.oaixdog.adminbackground.protocol.LoginResponse;
import com.oaixdog.adminbackground.protocol.LogoutRequest;
import com.oaixdog.adminbackground.service.LoginService;
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

import java.net.InetSocketAddress;

@Component
@RequiredArgsConstructor
public class LoginController {

    private static final Logger log = LoggerFactory.getLogger(LoginController.class);

    private final LoginService loginService;
    private final OperationLogService operationLogService;

    @PacketReceiver
    public void atLoginRequest(Session session, LoginRequest request, HttpAttachment attachment) {
        OperationLogService.Operation operation = operationLogService.start(attachment);
        try {
            if (request == null) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST);
            }
            LoginService.LoginResult result = loginService.login(request.getAccount(), request.getPassword(), clientIp(session, attachment));
            LoginResponse data = LoginResponse.valueOf(result.token(), result.id(), result.account(), result.username());
            attachment.setHttpResponseStatus(HttpResponseStatus.OK);
            NetContext.getRouter().send(session, ApiResponse.success(data), attachment);
            operationLogService.finish(operation, request, 0);
        } catch (BusinessException exception) {
            log.warn("登录业务失败: {}", exception.getMessage());
            attachment.setHttpResponseStatus(HttpResponseStatus.OK);
            NetContext.getRouter().send(session, ApiResponse.businessError(exception.getCode(), exception.getMessage()), attachment);
            operationLogService.finish(operation, request, exception.getCode());
        }
    }

    /**
     * 客户端 IP：直连时取连接地址；只有连接来自本机（同机 nginx 反向代理）时才信任 X-Real-IP，避免被伪造绕过 IP 限制
     */
    static String clientIp(Session session, HttpAttachment attachment) {
        String peer = session.getChannel().remoteAddress() instanceof InetSocketAddress address ? address.getAddress().getHostAddress() : "";
        String forwarded = attachment.getFullHttpRequest().headers().get("X-Real-IP");
        boolean fromLocalProxy = "127.0.0.1".equals(peer) || "0:0:0:0:0:0:0:1".equals(peer);
        return fromLocalProxy && forwarded != null && !forwarded.isBlank() ? forwarded.trim() : peer;
    }

    /** 退出登录：删除当前会话，令牌失效与否都返回成功 */
    @PacketReceiver
    public void atLogoutRequest(Session session, LogoutRequest request, HttpAttachment attachment) {
        OperationLogService.Operation operation = operationLogService.start(attachment);
        loginService.logout(attachment.getFullHttpRequest().headers().get(HttpHeaderNames.AUTHORIZATION));
        attachment.setHttpResponseStatus(HttpResponseStatus.OK);
        NetContext.getRouter().send(session, ApiResponse.success(null), attachment);
        operationLogService.finish(operation, null, 0);
    }
}
