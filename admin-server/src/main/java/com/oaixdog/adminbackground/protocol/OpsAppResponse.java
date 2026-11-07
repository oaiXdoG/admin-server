package com.oaixdog.adminbackground.protocol;

import com.oaixdog.adminbackground.entity.OpsApp;
import com.oaixdog.adminbackground.entity.OpsHost;
import lombok.Getter;
import lombok.Setter;

/** 主机连不上或读取失败时 state 为 null，error 为失败原因；busy：有任务正在执行 */
@Getter
@Setter
public class OpsAppResponse {

    private long id;
    private String name;
    private long hostId;
    private String hostIp;
    private String hostname;
    private boolean hostOnline;
    private String dir;
    private String remark;
    private long deployedAt;
    private long createdAt;
    private long updatedAt;
    private String error;
    private OpsAppState state;
    private boolean busy;

    public static OpsAppResponse valueOf(OpsApp app, OpsHost host) {
        OpsAppResponse response = new OpsAppResponse();
        response.id = app.getId();
        response.name = app.getName();
        response.hostId = app.getHostId();
        response.hostIp = host == null ? "" : host.getIp();
        response.hostname = host == null ? "" : host.getHostname();
        response.dir = app.getDir();
        response.remark = app.getRemark();
        response.deployedAt = app.getDeployedAt();
        response.createdAt = app.getCreatedAt();
        response.updatedAt = app.getUpdatedAt();
        response.error = "";
        return response;
    }
}
