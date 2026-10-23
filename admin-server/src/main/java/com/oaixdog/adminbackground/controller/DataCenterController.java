package com.oaixdog.adminbackground.controller;

import com.oaixdog.adminbackground.exception.BusinessException;
import com.oaixdog.adminbackground.exception.ErrorCode;
import com.oaixdog.adminbackground.protocol.ApiResponse;
import com.oaixdog.adminbackground.protocol.DataQueryExportRequest;
import com.oaixdog.adminbackground.protocol.DataQueryRequest;
import com.oaixdog.adminbackground.protocol.DataSourceCreateRequest;
import com.oaixdog.adminbackground.protocol.DataSourceListRequest;
import com.oaixdog.adminbackground.protocol.DataSourceQueriesRequest;
import com.oaixdog.adminbackground.protocol.DataSourceRemoveRequest;
import com.oaixdog.adminbackground.protocol.DataSourceTestRequest;
import com.oaixdog.adminbackground.protocol.DataSourceUpdateRequest;
import com.oaixdog.adminbackground.protocol.DataTemplateCreateRequest;
import com.oaixdog.adminbackground.protocol.DataTemplateListRequest;
import com.oaixdog.adminbackground.protocol.DataTemplateRemoveRequest;
import com.oaixdog.adminbackground.protocol.DataTemplateUpdateRequest;
import com.oaixdog.adminbackground.service.DataQueryService;
import com.oaixdog.adminbackground.service.DataSourceService;
import com.oaixdog.adminbackground.service.DataTemplateService;
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

/** 数据中心：业务服务器数据源、查询模板和数据查询 */
@Component
@RequiredArgsConstructor
public class DataCenterController {

    private final DataSourceService dataSourceService;
    private final DataTemplateService dataTemplateService;
    private final DataQueryService dataQueryService;
    private final OperationLogService operationLogService;

    @PacketReceiver
    public void atDataSourceListRequest(Session session, DataSourceListRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> dataSourceService.list(auth(attachment)));
    }

    @PacketReceiver
    public void atDataSourceCreateRequest(Session session, DataSourceCreateRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> dataSourceService.create(auth(attachment), request.getName(),
                request.getUrl(), request.getSecret(), request.getTimeoutMs(), request.getRemark()));
    }

    @PacketReceiver
    public void atDataSourceUpdateRequest(Session session, DataSourceUpdateRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> dataSourceService.update(auth(attachment), request.getSourceId(),
                request.getName(), request.getUrl(), request.getSecret(), request.getTimeoutMs(),
                request.getRemark()));
    }

    @PacketReceiver
    public void atDataSourceRemoveRequest(Session session, DataSourceRemoveRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> {
            dataSourceService.remove(auth(attachment), request.getSourceId());
            return null;
        });
    }

    @PacketReceiver
    public void atDataSourceTestRequest(Session session, DataSourceTestRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> dataSourceService.test(auth(attachment), request.getSourceId(),
                request.getUrl(), request.getSecret(), request.getTimeoutMs()));
    }

    @PacketReceiver
    public void atDataSourceQueriesRequest(Session session, DataSourceQueriesRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> dataSourceService.queries(auth(attachment), request.getSourceId()));
    }


    @PacketReceiver
    public void atDataTemplateListRequest(Session session, DataTemplateListRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> Map.of("list", dataTemplateService.list(auth(attachment))));
    }

    @PacketReceiver
    public void atDataTemplateCreateRequest(Session session, DataTemplateCreateRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> dataTemplateService.create(auth(attachment), request.getSourceId(),
                request.getName(), request.getQuery(), request.getParams(), request.getFields()));
    }

    @PacketReceiver
    public void atDataTemplateUpdateRequest(Session session, DataTemplateUpdateRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> dataTemplateService.update(auth(attachment), request.getTemplateId(),
                request.getSourceId(), request.getName(), request.getQuery(), request.getParams(), request.getFields()));
    }

    @PacketReceiver
    public void atDataTemplateRemoveRequest(Session session, DataTemplateRemoveRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> {
            dataTemplateService.remove(auth(attachment), request.getTemplateId());
            return null;
        });
    }

    @PacketReceiver
    public void atDataQueryRequest(Session session, DataQueryRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> dataQueryService.query(auth(attachment), request.getTemplateId(),
                request.getParams(), request.getPage(), request.getPageSize()));
    }

    @PacketReceiver
    public void atDataQueryExportRequest(Session session, DataQueryExportRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> dataQueryService.export(auth(attachment), request.getTemplateId(),
                request.getParams()));
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
