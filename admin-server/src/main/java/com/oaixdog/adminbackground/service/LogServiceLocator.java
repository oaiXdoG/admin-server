package com.oaixdog.adminbackground.service;

import com.oaixdog.adminbackground.exception.BusinessException;
import com.oaixdog.adminbackground.exception.ErrorCode;
import com.zfoo.net.NetContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 日志服务是否在线：以日志服务启动时通过 ServerReport 上报的服务类型 log 为准，不单独展示节点。
 */
@Service
@RequiredArgsConstructor
public class LogServiceLocator {

    private static final String SERVER_TYPE = "log";

    private final ServerRegistryService serverRegistryService;

    public void requireAvailable() {
        if (serverRegistryService.listByType(SERVER_TYPE).isEmpty()) {
            throw new BusinessException(ErrorCode.LOG_SERVICE_UNAVAILABLE);
        }
    }

    /** 确认日志服务在线后同步调用（zfoo RPC），失败或超时时抛 failed */
    public <T> T ask(Object packet, Class<T> answerClass, ErrorCode failed) {
        requireAvailable();
        try {
            return NetContext.getConsumer().syncAsk(packet, answerClass, null).packet();
        } catch (Exception exception) {
            throw new BusinessException(failed, exception.getMessage());
        }
    }
}
