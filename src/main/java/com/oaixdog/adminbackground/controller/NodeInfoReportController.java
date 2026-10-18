package com.oaixdog.adminbackground.controller;

import com.oaixdog.adminbackground.exception.BusinessException;
import com.oaixdog.adminbackground.exception.ErrorCode;
import com.oaixdog.adminbackground.protocol.ApiResponse;
import com.oaixdog.adminbackground.protocol.NodeInfoReportRequest;
import com.oaixdog.adminbackground.service.AgentService;
import com.oaixdog.adminbackground.service.NodeInfoReportService;
import com.zfoo.net.NetContext;
import com.zfoo.net.anno.PacketReceiver;
import com.zfoo.net.router.attachment.HttpAttachment;
import com.zfoo.net.session.Session;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 业务服调用的接口：定时上报节点信息。不需要登录，按项目上报密钥验签 */
@Component
@RequiredArgsConstructor
public class NodeInfoReportController {

    private final NodeInfoReportService nodeInfoReportService;

    @PacketReceiver
    public void atNodeInfoReportRequest(Session session, NodeInfoReportRequest request, HttpAttachment attachment) {
        try {
            if (request == null) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST);
            }
            HttpHeaders headers = attachment.getFullHttpRequest().headers();
            nodeInfoReportService.report(headers.get("X-Admin-Project"), headers.get("X-Admin-Timestamp"), headers.get("X-Admin-Signature"),
                    headers.get(AgentService.BODY_DIGEST_HEADER), request, LoginController.clientIp(session, attachment));
            attachment.setHttpResponseStatus(HttpResponseStatus.OK);
            NetContext.getRouter().send(session, ApiResponse.success(null), attachment);
        } catch (BusinessException exception) {
            attachment.setHttpResponseStatus(HttpResponseStatus.OK);
            NetContext.getRouter().send(session, ApiResponse.businessError(exception.getCode(), exception.getMessage()), attachment);
        }
    }
}
