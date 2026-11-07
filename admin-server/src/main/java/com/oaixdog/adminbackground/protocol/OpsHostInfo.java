package com.oaixdog.adminbackground.protocol;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** 主机的实时信息：通过 SSH 读取 /proc、df（CPU、网络为 1 秒内的平均值） */
@Getter
@Setter
public class OpsHostInfo {

    private String hostname;
    private String os;
    private int cpuCores;
    /** 0～1 */
    private double cpuUsage;
    private long memoryTotal;
    private long memoryUsed;
    private List<Disk> disks;
    /** 秒 */
    private long uptime;
    /** 字节/秒 */
    private double netIn;
    private double netOut;

    public record Disk(String mount, long total, long used) {
    }
}
