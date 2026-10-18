package com.oaixdog.adminbackground.controller;

import com.oaixdog.adminbackground.exception.BusinessException;
import com.oaixdog.adminbackground.exception.ErrorCode;
import com.oaixdog.adminbackground.protocol.ApiResponse;
import com.oaixdog.adminbackground.protocol.ServerDeployRequest;
import com.oaixdog.adminbackground.protocol.ServerDeployUploadRequest;
import com.oaixdog.adminbackground.protocol.ServerFileRequest;
import com.oaixdog.adminbackground.protocol.ServerFileResultRequest;
import com.oaixdog.adminbackground.protocol.ServerHostCreateRequest;
import com.oaixdog.adminbackground.protocol.ServerHostInstallRequest;
import com.oaixdog.adminbackground.protocol.ServerHostListRequest;
import com.oaixdog.adminbackground.protocol.ServerHostRemoveRequest;
import com.oaixdog.adminbackground.protocol.ServerHostUpdateRequest;
import com.oaixdog.adminbackground.protocol.ServerProcessCreateRequest;
import com.oaixdog.adminbackground.protocol.ServerProcessListRequest;
import com.oaixdog.adminbackground.protocol.ServerProcessOperateRequest;
import com.oaixdog.adminbackground.protocol.ServerProcessRemoveRequest;
import com.oaixdog.adminbackground.protocol.ServerProcessTypeListRequest;
import com.oaixdog.adminbackground.protocol.ServerProcessTypeRemoveRequest;
import com.oaixdog.adminbackground.protocol.ServerProcessTypeSaveRequest;
import com.oaixdog.adminbackground.protocol.ServerProcessUpdateRequest;
import com.oaixdog.adminbackground.protocol.ServerMetricQueryRequest;
import com.oaixdog.adminbackground.protocol.ServerNodeUnmanagedRequest;
import com.oaixdog.adminbackground.protocol.ServerReportConfigRequest;
import com.oaixdog.adminbackground.protocol.ServerReportConfigResetRequest;
import com.oaixdog.adminbackground.protocol.ServerTaskListRequest;
import com.oaixdog.adminbackground.protocol.ServerTemplateListRequest;
import com.oaixdog.adminbackground.protocol.ServerTemplateRemoveRequest;
import com.oaixdog.adminbackground.protocol.ServerTemplateSaveRequest;
import com.oaixdog.adminbackground.service.OperationLogService;
import com.oaixdog.adminbackground.service.ServerDeployService;
import com.oaixdog.adminbackground.service.ServerFileService;
import com.oaixdog.adminbackground.service.ServerHostService;
import com.oaixdog.adminbackground.service.ServerProcessService;
import com.oaixdog.adminbackground.service.ServerProcessTypeService;
import com.oaixdog.adminbackground.service.ServerTemplateService;
import com.zfoo.net.NetContext;
import com.zfoo.net.anno.PacketReceiver;
import com.zfoo.net.router.attachment.HttpAttachment;
import com.zfoo.net.session.Session;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 服务器管理：服务器、托管进程、JVM 参数模板、执行记录 */
@Component
@RequiredArgsConstructor
public class ServerController {

    private final ServerHostService hostService;
    private final ServerProcessService processService;
    private final ServerTemplateService templateService;
    private final ServerProcessTypeService typeService;
    private final ServerDeployService deployService;
    private final ServerFileService fileService;
    private final OperationLogService operationLogService;

    @PacketReceiver
    public void atServerHostListRequest(Session session, ServerHostListRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> Map.of("list", hostService.list(auth(attachment), request.getProjectId())));
    }

    @PacketReceiver
    public void atServerHostCreateRequest(Session session, ServerHostCreateRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> hostService.create(auth(attachment), request.getProjectId(), request.getName(), request.getGroupName(), request.getRemark()));
    }

    @PacketReceiver
    public void atServerHostUpdateRequest(Session session, ServerHostUpdateRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> hostService.update(auth(attachment), request.getProjectId(), request.getServerId(), request.getName(), request.getGroupName(), request.getRemark()));
    }

    @PacketReceiver
    public void atServerHostRemoveRequest(Session session, ServerHostRemoveRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> {
            hostService.remove(auth(attachment), request.getProjectId(), request.getServerId());
            return null;
        });
    }

    @PacketReceiver
    public void atServerHostInstallRequest(Session session, ServerHostInstallRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> Map.of("installCommand", hostService.install(auth(attachment), request.getProjectId(), request.getServerId())));
    }

    @PacketReceiver
    public void atServerProcessListRequest(Session session, ServerProcessListRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> Map.of("list", processService.list(auth(attachment), request.getProjectId())));
    }

    @PacketReceiver
    public void atServerProcessCreateRequest(Session session, ServerProcessCreateRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> processService.create(auth(attachment), request.getProjectId(), request.getServerId(), request.getName(), definition(request.getTypeId(), request.getRemark(),
                request.getWorkDir(), request.getJavaPath(), request.getTemplateId(), request.getJvmOptions(), request.getJar(), request.getClasspath(), request.getMainClass(), request.getProgramArgs(), request.getEnv(),
                request.getRunUser(), request.getPort())));
    }

    @PacketReceiver
    public void atServerProcessUpdateRequest(Session session, ServerProcessUpdateRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> processService.update(auth(attachment), request.getProjectId(), request.getProcessId(), definition(request.getTypeId(), request.getRemark(), request.getWorkDir(),
                request.getJavaPath(), request.getTemplateId(), request.getJvmOptions(), request.getJar(), request.getClasspath(), request.getMainClass(), request.getProgramArgs(), request.getEnv(), request.getRunUser(),
                request.getPort())));
    }

    @PacketReceiver
    public void atServerProcessRemoveRequest(Session session, ServerProcessRemoveRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> {
            processService.remove(auth(attachment), request.getProjectId(), request.getProcessId());
            return null;
        });
    }

    @PacketReceiver
    public void atServerProcessOperateRequest(Session session, ServerProcessOperateRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> Map.of("taskIds", processService.operate(auth(attachment), request.getProjectId(), request.getProcessIds(), request.getAction())));
    }

    @PacketReceiver
    public void atServerMetricQueryRequest(Session session, ServerMetricQueryRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> Map.of("list", hostService.metrics(auth(attachment), request.getProjectId(), request.getServerId(),
                request.getProcessId(), request.getStartTime(), request.getEndTime(), request.getStep())));
    }

    @PacketReceiver
    public void atServerTaskListRequest(Session session, ServerTaskListRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> processService.tasks(auth(attachment), request.getProjectId(), request.getProcessId(), request.getPage(), request.getPageSize()));
    }

    @PacketReceiver
    public void atServerTemplateListRequest(Session session, ServerTemplateListRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> Map.of("list", templateService.list(auth(attachment), request.getProjectId())));
    }

    @PacketReceiver
    public void atServerTemplateSaveRequest(Session session, ServerTemplateSaveRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> templateService.save(auth(attachment), request.getProjectId(), request.getTemplateId(), request.getName(), request.getOptions()));
    }

    @PacketReceiver
    public void atServerTemplateRemoveRequest(Session session, ServerTemplateRemoveRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> {
            templateService.remove(auth(attachment), request.getProjectId(), request.getTemplateId());
            return null;
        });
    }

    @PacketReceiver
    public void atServerProcessTypeListRequest(Session session, ServerProcessTypeListRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> Map.of("list", typeService.list(auth(attachment), request.getProjectId())));
    }

    @PacketReceiver
    public void atServerProcessTypeSaveRequest(Session session, ServerProcessTypeSaveRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> typeService.save(auth(attachment), request.getProjectId(), request.getTypeId(), request.getName(), request.getCode()));
    }

    @PacketReceiver
    public void atServerNodeUnmanagedRequest(Session session, ServerNodeUnmanagedRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> Map.of("list", processService.unmanaged(auth(attachment), request.getProjectId())));
    }

    @PacketReceiver
    public void atServerReportConfigRequest(Session session, ServerReportConfigRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> processService.reportConfig(auth(attachment), request.getProjectId()));
    }

    @PacketReceiver
    public void atServerReportConfigResetRequest(Session session, ServerReportConfigResetRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> processService.resetReportConfig(auth(attachment), request.getProjectId()));
    }

    @PacketReceiver
    public void atServerProcessTypeRemoveRequest(Session session, ServerProcessTypeRemoveRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> {
            typeService.remove(auth(attachment), request.getProjectId(), request.getTypeId());
            return null;
        });
    }

    @PacketReceiver
    public void atServerDeployUploadRequest(Session session, ServerDeployUploadRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> deployService.upload(auth(attachment), request.getProjectId(), request.getProcessId(), request.getUploadId(),
                request.getIndex(), request.getTotal(), request.getData()));
    }

    @PacketReceiver
    public void atServerDeployRequest(Session session, ServerDeployRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> deployService.deploy(auth(attachment), request.getProjectId(), request.getProcessId(), request.getUploadId(),
                request.getFileName(), request.getRemark()));
    }

    @PacketReceiver
    public void atServerFileRequest(Session session, ServerFileRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> fileService.request(auth(attachment), request.getProjectId(), request.getProcessId(), request.getOp(),
                request.getPath(), request.getContent()));
    }

    @PacketReceiver
    public void atServerFileResultRequest(Session session, ServerFileResultRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> fileService.result(auth(attachment), request.getProjectId(), request.getOpId()));
    }

    private static ServerProcessService.Definition definition(Long typeId, String remark, String workDir, String javaPath, Long templateId, String jvmOptions, String jar,
                                                              String classpath, String mainClass, String programArgs, String env, String runUser, Integer port) {
        return new ServerProcessService.Definition(typeId, remark, workDir, javaPath, templateId, jvmOptions, jar, classpath, mainClass, programArgs, env, runUser, port);
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
