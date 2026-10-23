package com.oaixdog.adminbackground.protocol;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** 备份设置、最近一次结果和已有备份文件（按时间倒序）；lastSuccess 为空表示还没有备份过 */
@Getter
@Setter
public class BackupResponse {

    private boolean enabled;
    private String time;
    private int keep;
    private String dir;
    private boolean running;
    private Long lastTime;
    private Boolean lastSuccess;
    private String lastMessage;
    private List<BackupFile> files;

    public record BackupFile(String name, long size, long createdAt) {
    }
}
