package com.oaixdog.adminbackground.controller;

import com.oaixdog.adminbackground.exception.BusinessException;
import com.oaixdog.adminbackground.exception.ErrorCode;
import com.oaixdog.adminbackground.protocol.ApiResponse;
import com.oaixdog.adminbackground.protocol.BackupDownloadRequest;
import com.oaixdog.adminbackground.protocol.BackupGetRequest;
import com.oaixdog.adminbackground.protocol.BackupRemoveRequest;
import com.oaixdog.adminbackground.protocol.BackupRunRequest;
import com.oaixdog.adminbackground.protocol.BackupUpdateRequest;
import com.oaixdog.adminbackground.service.BackupService;
import com.oaixdog.adminbackground.service.OperationLogService;
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
public class BackupController {

    private final BackupService backupService;
    private final OperationLogService operationLogService;

    @PacketReceiver
    public void atBackupGetRequest(Session session, BackupGetRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> backupService.get(auth(attachment)));
    }

    @PacketReceiver
    public void atBackupUpdateRequest(Session session, BackupUpdateRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> backupService.update(auth(attachment), request.getEnabled(), request.getTime(), request.getKeep()));
    }

    @PacketReceiver
    public void atBackupRunRequest(Session session, BackupRunRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> backupService.run(auth(attachment)));
    }

    @PacketReceiver
    public void atBackupDownloadRequest(Session session, BackupDownloadRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> backupService.download(auth(attachment), request.getName()));
    }

    @PacketReceiver
    public void atBackupRemoveRequest(Session session, BackupRemoveRequest request, HttpAttachment attachment) {
        handle(session, attachment, request, () -> {
            backupService.remove(auth(attachment), request.getName());
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
