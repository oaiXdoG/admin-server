package com.oaixdog.adminbackground.controller;

import com.oaixdog.adminbackground.exception.BusinessException;
import com.oaixdog.adminbackground.exception.ErrorCode;
import com.oaixdog.adminbackground.protocol.ApiResponse;
import com.oaixdog.adminbackground.protocol.LogEventCreateRequest;
import com.oaixdog.adminbackground.protocol.LogEventListRequest;
import com.oaixdog.adminbackground.protocol.LogEventUpdateRequest;
import com.oaixdog.adminbackground.protocol.LogQueryRequest;
import com.oaixdog.adminbackground.service.LogEventService;
import com.oaixdog.adminbackground.service.LogQueryService;
import com.oaixdog.adminbackground.service.OperationLogService;
import com.zfoo.net.NetContext;
import com.zfoo.net.anno.PacketReceiver;
import com.zfoo.net.router.attachment.HttpAttachment;
import com.zfoo.net.session.Session;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 日志中心：日志事件和日志查询 */
@Component
@RequiredArgsConstructor
public class LogCenterController {

    private final LogEventService logEventService;
    private final LogQueryService logQueryService;
    private final OperationLogService operationLogService;

    @PacketReceiver
    public void atLogEventListRequest(Session session, LogEventListRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> Map.of("list", logEventService.list(auth(attachment))));
    }

    @PacketReceiver
    public void atLogEventCreateRequest(Session session, LogEventCreateRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> {
            logEventService.create(auth(attachment), request.getEvent(), request.getName(), request.getCollection(), request.getFields());
            return null;
        });
    }

    @PacketReceiver
    public void atLogEventUpdateRequest(Session session, LogEventUpdateRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> {
            logEventService.update(auth(attachment), request.getEvent(), request.getName(), request.getCollection(), request.getFields());
            return null;
        });
    }

    @PacketReceiver
    public void atLogQueryRequest(Session session, LogQueryRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> logQueryService.query(auth(attachment), request.getEvent(),
                request.getConditions(), request.getOffset(), request.getLimit()));
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
