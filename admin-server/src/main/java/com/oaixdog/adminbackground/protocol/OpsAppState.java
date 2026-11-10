package com.oaixdog.adminbackground.protocol;

import lombok.Getter;
import lombok.Setter;

/**
 * 程序的运行状态：通过 SSH 执行程序目录里的 bin/app.sh status 读取。
 * status：running（运行中）、stopped（已停止）、not-deployed（lib 下还没有 jar）、no-script（bin/app.sh 不存在，重新保存登记或发布会拷过去）。
 */
@Getter
@Setter
public class OpsAppState {

    public static final String RUNNING = "running";
    public static final String STOPPED = "stopped";
    public static final String NOT_DEPLOYED = "not-deployed";
    public static final String NO_SCRIPT = "no-script";

    private String dir;
    private String error;
    private String status;
    /** lib 下有 jar */
    private boolean deployed;
    private boolean running;
    private long pid;
    /** 毫秒 */
    private long startTime;
    /** 常驻内存，字节 */
    private long memory;
    /** 0～1，两次查询之间的平均值，第一次查询为 0 */
    private double cpuUsage;
}
