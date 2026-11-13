package com.oaixdog.adminbackground.protocol;

import com.oaixdog.adminbackground.entity.OpsApp;
import lombok.Getter;
import lombok.Setter;

/** 机器连不上或读取失败时 state 为 null，error 为失败原因；busy：有任务正在执行 */
@Getter
@Setter
public class OpsAppResponse {

    private long id;
    private String name;
    private String ip;
    private int sshPort;
    private boolean hostOnline;
    private String dir;
    private String remark;
    private long deployedAt;
    private long createdAt;
    private long updatedAt;
    private String error;
    private OpsAppState state;
    private boolean busy;

    public static OpsAppResponse valueOf(OpsApp app) {
        OpsAppResponse response = new OpsAppResponse();
        response.id = app.getId();
        response.name = app.getName();
        response.ip = app.getIp();
        response.sshPort = app.getSshPort() == null ? 22 : app.getSshPort();
        response.dir = app.getDir();
        response.remark = app.getRemark();
        response.deployedAt = app.getDeployedAt();
        response.createdAt = app.getCreatedAt();
        response.updatedAt = app.getUpdatedAt();
        response.error = "";
        return response;
    }
}
