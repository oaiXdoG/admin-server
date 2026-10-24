package com.oaixdog.adminbackground.controller;

import com.oaixdog.adminbackground.service.ServerRegistryService;
import com.oaixdog.protocol.admin.ServerReport;
import com.zfoo.net.NetContext;
import com.zfoo.net.anno.PacketReceiver;
import com.zfoo.net.router.attachment.SignalAttachment;
import com.zfoo.net.session.Session;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ServerReportController {

    private final ServerRegistryService serverRegistryService;

    @PacketReceiver
    public void atServerReport(Session session, ServerReport report, SignalAttachment attachment) {
        NetContext.getRouter().send(session, serverRegistryService.report(session, report), attachment);
    }
}
