package com.oaixdog.adminbackground.protocol;

import com.oaixdog.adminbackground.entity.OpsHost;
import lombok.Getter;
import lombok.Setter;

/** online：能否通过 SSH 连上；连不上或读取失败时 info 为 null，error 为原因 */
@Getter
@Setter
public class OpsHostResponse {

    private long id;
    private String ip;
    private int sshPort;
    private String hostname;
    private String remark;
    private boolean online;
    private long initializedAt;
    private long lastSeenAt;
    private long createdAt;
    private long appCount;
    private long runningCount;
    private String error;
    private OpsHostInfo info;

    public static OpsHostResponse valueOf(OpsHost host) {
        OpsHostResponse response = new OpsHostResponse();
        response.id = host.getId();
        response.ip = host.getIp();
        response.sshPort = host.getSshPort() == null ? 22 : host.getSshPort();
        response.initializedAt = host.getInitializedAt() == null ? 0 : host.getInitializedAt();
        response.hostname = host.getHostname();
        response.remark = host.getRemark();
        response.lastSeenAt = host.getLastSeenAt();
        response.createdAt = host.getCreatedAt();
        response.error = "";
        return response;
    }
}
