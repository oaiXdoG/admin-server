package com.oaixdog.adminbackground.controller;

import com.oaixdog.adminbackground.exception.BusinessException;
import com.oaixdog.adminbackground.exception.ErrorCode;
import com.oaixdog.adminbackground.protocol.ApiResponse;
import com.oaixdog.adminbackground.protocol.OpsAppConfigGetRequest;
import com.oaixdog.adminbackground.protocol.OpsAppConfigListRequest;
import com.oaixdog.adminbackground.protocol.OpsAppConfigSaveRequest;
import com.oaixdog.adminbackground.protocol.OpsAppControlRequest;
import com.oaixdog.adminbackground.protocol.OpsAppCreateRequest;
import com.oaixdog.adminbackground.protocol.OpsAppDeployRequest;
import com.oaixdog.adminbackground.protocol.OpsAppListRequest;
import com.oaixdog.adminbackground.protocol.OpsAppLogRequest;
import com.oaixdog.adminbackground.protocol.OpsAppOptionsRequest;
import com.oaixdog.adminbackground.protocol.OpsAppRemoveRequest;
import com.oaixdog.adminbackground.protocol.OpsAppUpdateRequest;
import com.oaixdog.adminbackground.protocol.OpsAppUploadChunkRequest;
import com.oaixdog.adminbackground.protocol.OpsAppUploadStartRequest;
import com.oaixdog.adminbackground.protocol.OpsHostListRequest;
import com.oaixdog.adminbackground.protocol.OpsHostOptionsRequest;
import com.oaixdog.adminbackground.protocol.OpsHostRemoveRequest;
import com.oaixdog.adminbackground.protocol.OpsHostUpdateRequest;
import com.oaixdog.adminbackground.protocol.OpsHostCreateRequest;
import com.oaixdog.adminbackground.protocol.OpsHostTestRequest;
import com.oaixdog.adminbackground.protocol.OpsHostInitRequest;
import com.oaixdog.adminbackground.protocol.OpsAppBackupsRequest;
import com.oaixdog.adminbackground.protocol.OpsAppRollbackRequest;
import com.oaixdog.adminbackground.protocol.OpsTaskGetRequest;
import com.oaixdog.adminbackground.service.OperationLogService;
import com.oaixdog.adminbackground.service.OpsAppService;
import com.oaixdog.adminbackground.service.OpsHostService;
import com.oaixdog.adminbackground.service.OpsMonitorService;
import com.oaixdog.adminbackground.service.OpsTaskService;
import com.zfoo.net.NetContext;
import com.zfoo.net.anno.PacketReceiver;
import com.zfoo.net.router.attachment.HttpAttachment;
import com.zfoo.net.session.Session;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 运维中心：主机、程序和监控的下拉选项。主机、程序通过 SSH 管理（启停、发布等返回任务 id，页面轮询输出）；监控曲线在 Grafana */
@Component
@RequiredArgsConstructor
public class OpsCenterController {

    private final OpsHostService opsHostService;
    private final OpsAppService opsAppService;
    private final OpsMonitorService opsMonitorService;
    private final OpsTaskService opsTaskService;
    private final OperationLogService operationLogService;

    @PacketReceiver
    public void atOpsHostListRequest(Session session, OpsHostListRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> opsHostService.list(auth(attachment)));
    }

    @PacketReceiver
    public void atOpsHostOptionsRequest(Session session, OpsHostOptionsRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> opsHostService.options(auth(attachment)));
    }

    @PacketReceiver
    public void atOpsHostUpdateRequest(Session session, OpsHostUpdateRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> {
            opsHostService.update(auth(attachment), request.getHostId(), request.getIp(), request.getSshPort(), request.getRemark());
            return null;
        });
    }

    @PacketReceiver
    public void atOpsHostRemoveRequest(Session session, OpsHostRemoveRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> {
            opsHostService.remove(auth(attachment), request.getHostId());
            return null;
        });
    }

    @PacketReceiver
    public void atOpsAppListRequest(Session session, OpsAppListRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> opsAppService.list(auth(attachment), request.getHostId()));
    }

    @PacketReceiver
    public void atOpsAppCreateRequest(Session session, OpsAppCreateRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> opsAppService.create(auth(attachment), request.getName(), request.getHostId(),
                request.getDir(), request.getRemark()));
    }

    @PacketReceiver
    public void atOpsAppUpdateRequest(Session session, OpsAppUpdateRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> opsAppService.update(auth(attachment), request.getAppId(), request.getName(),
                request.getHostId(), request.getDir(), request.getRemark()));
    }

    @PacketReceiver
    public void atOpsAppRemoveRequest(Session session, OpsAppRemoveRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> {
            opsAppService.remove(auth(attachment), request.getAppId());
            return null;
        });
    }

    @PacketReceiver
    public void atOpsAppControlRequest(Session session, OpsAppControlRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> Map.of("taskId", opsAppService.control(auth(attachment), request.getAppId(), request.getAction())));
    }

    @PacketReceiver
    public void atOpsAppLogRequest(Session session, OpsAppLogRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> opsAppService.log(auth(attachment), request.getAppId(), request.getLines(), request.getFile()));
    }

    @PacketReceiver
    public void atOpsAppConfigListRequest(Session session, OpsAppConfigListRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> opsAppService.configList(auth(attachment), request.getAppId()));
    }

    @PacketReceiver
    public void atOpsAppConfigGetRequest(Session session, OpsAppConfigGetRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> opsAppService.configGet(auth(attachment), request.getAppId(), request.getFile()));
    }

    @PacketReceiver
    public void atOpsAppConfigSaveRequest(Session session, OpsAppConfigSaveRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () ->
                Map.of("taskId", opsAppService.configSave(auth(attachment), request.getAppId(), request.getFile(), request.getContent(),
                        request.getRestart())));
    }

    @PacketReceiver
    public void atOpsAppUploadStartRequest(Session session, OpsAppUploadStartRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> opsAppService.uploadStart(auth(attachment), request.getAppId()));
    }

    @PacketReceiver
    public void atOpsAppUploadChunkRequest(Session session, OpsAppUploadChunkRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> {
            opsAppService.uploadChunk(auth(attachment), request.getAppId(), request.getUploadId(), request.getIndex(), request.getData());
            return null;
        });
    }

    @PacketReceiver
    public void atOpsAppDeployRequest(Session session, OpsAppDeployRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () ->
                Map.of("taskId", opsAppService.deploy(auth(attachment), request.getAppId(), request.getUploadId(), request.getOverwriteConf())));
    }

    @PacketReceiver
    public void atOpsAppOptionsRequest(Session session, OpsAppOptionsRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> opsMonitorService.appOptions(auth(attachment), request.getHostId()));
    }

    @PacketReceiver
    public void atOpsHostCreateRequest(Session session, OpsHostCreateRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> opsHostService.create(auth(attachment), request.getIp(), request.getSshPort(),
                request.getRemark()));
    }

    @PacketReceiver
    public void atOpsHostTestRequest(Session session, OpsHostTestRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> Map.of("message", opsHostService.test(auth(attachment), request.getHostId())));
    }

    @PacketReceiver
    public void atOpsHostInitRequest(Session session, OpsHostInitRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> Map.of("taskId", opsHostService.init(auth(attachment), request.getHostId())));
    }

    @PacketReceiver
    public void atOpsAppBackupsRequest(Session session, OpsAppBackupsRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> opsAppService.backups(auth(attachment), request.getAppId()));
    }

    @PacketReceiver
    public void atOpsAppRollbackRequest(Session session, OpsAppRollbackRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> Map.of("taskId", opsAppService.rollback(auth(attachment), request.getAppId(),
                request.getBackup())));
    }

    @PacketReceiver
    public void atOpsTaskGetRequest(Session session, OpsTaskGetRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> opsTaskService.get(auth(attachment), request.getTaskId(), request.getOffset()));
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
