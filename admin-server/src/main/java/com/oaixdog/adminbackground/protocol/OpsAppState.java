package com.oaixdog.adminbackground.protocol;

import lombok.Getter;
import lombok.Setter;

/**
 * 程序的运行状态：通过 SSH 读取 systemd 服务 danmu-&lt;名称&gt; 的状态。
 * status：systemd 的 ActiveState（active、inactive、failed、activating 等）；服务还没生成时为 not-found。
 * unmanaged：不归 systemd 管、用 bin/start.sh 在后台启动的进程（例如以前的 host 启动的），下次启动或发布时会被接管。
 */
@Getter
@Setter
public class OpsAppState {

    private String dir;
    private String error;
    /** lib 下有 jar */
    private boolean deployed;
    private boolean running;
    private boolean unmanaged;
    private String status;
    /** systemd 自动拉起的次数（从上次手动启动算起） */
    private long restarts;
    private long pid;
    /** 毫秒 */
    private long startTime;
    /** 字节 */
    private long memory;
    /** 0～1，两次查询之间的平均值，第一次查询为 0 */
    private double cpuUsage;
}
