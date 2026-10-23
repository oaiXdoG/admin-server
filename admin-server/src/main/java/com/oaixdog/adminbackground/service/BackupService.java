package com.oaixdog.adminbackground.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.oaixdog.adminbackground.config.ApplicationProperties;
import com.oaixdog.adminbackground.entity.SystemSetting;
import com.oaixdog.adminbackground.exception.BusinessException;
import com.oaixdog.adminbackground.exception.ErrorCode;
import com.oaixdog.adminbackground.mapper.SystemSettingMapper;
import com.oaixdog.adminbackground.protocol.BackupResponse;
import com.zfoo.protocol.util.StringUtils;
import com.zfoo.scheduler.anno.Scheduler;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 数据库备份：用 pg_dump 把 admin-background 的 PostgreSQL 导出为自定义格式文件（压缩），恢复用 pg_restore。
 * 设置存在 system_setting 的 backup.* 键里：每天定时备份、保留份数；也可以在页面上立即备份、下载、删除。
 * 备份目录和 pg_dump 路径来自 application.properties。同一时间只执行一个备份。
 */
@Service
@RequiredArgsConstructor
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);

    private static final String VIEW_MENU = "manage_setting";
    private static final String EDIT_BUTTON = "manage_setting:backup";
    private static final String ENABLED = "backup.enabled";
    private static final String TIME = "backup.time";
    private static final String KEEP = "backup.keep";
    private static final String LAST_TIME = "backup.lastTime";
    private static final String LAST_SUCCESS = "backup.lastSuccess";
    private static final String LAST_MESSAGE = "backup.lastMessage";
    private static final String DEFAULT_TIME = "03:00";
    private static final int DEFAULT_KEEP = 7;
    private static final int KEEP_MAX = 90;
    private static final long DOWNLOAD_MAX_BYTES = 50L * 1024 * 1024;
    private static final long TIMEOUT_MINUTES = 30;
    private static final int MESSAGE_MAX_LENGTH = 500;
    private static final Pattern FILE_NAME = Pattern.compile("^admin_background-\\d{8}-\\d{6}\\.dump$");
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    private final ApplicationProperties properties;
    private final PermissionService permissionService;
    private final SystemSettingMapper systemSettingMapper;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "database-backup");
        thread.setDaemon(true);
        return thread;
    });
    /** 定时备份最近一次触发的日期，同一天只触发一次 */
    private volatile LocalDate lastScheduledDate;

    public BackupResponse get(String authorization) {
        permissionService.requireMenu(authorization, VIEW_MENU);
        return response();
    }

    public BackupResponse update(String authorization, Boolean enabled, String time, Integer keep) {
        permissionService.requireMenu(authorization, EDIT_BUTTON);
        if (enabled == null || keep == null || keep < 1 || keep > KEEP_MAX || parseClock(time) == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        save(Map.of(ENABLED, String.valueOf(enabled), TIME, time.trim(), KEEP, String.valueOf(keep)));
        return response();
    }

    /** 立即备份：在后台线程执行，页面通过 get 查看进度和结果 */
    public BackupResponse run(String authorization) {
        permissionService.requireMenu(authorization, EDIT_BUTTON);
        if (!start()) {
            throw new BusinessException(ErrorCode.BACKUP_RUNNING);
        }
        return response();
    }

    /** 返回文件名和 base64 内容，由页面保存为文件 */
    public Map<String, Object> download(String authorization, String name) {
        permissionService.requireMenu(authorization, EDIT_BUTTON);
        Path file = requireFile(name);
        try {
            if (Files.size(file) > DOWNLOAD_MAX_BYTES) {
                throw new BusinessException(ErrorCode.BACKUP_TOO_LARGE);
            }
            return Map.of("name", name, "data", Base64.getEncoder().encodeToString(Files.readAllBytes(file)));
        } catch (IOException exception) {
            throw new IllegalStateException("读取备份文件失败: " + file, exception);
        }
    }

    public void remove(String authorization, String name) {
        permissionService.requireMenu(authorization, EDIT_BUTTON);
        Path file = requireFile(name);
        try {
            Files.deleteIfExists(file);
        } catch (IOException exception) {
            throw new IllegalStateException("删除备份文件失败: " + file, exception);
        }
    }

    /** 每分钟检查一次：已开启且到了设定时间，并且今天还没触发过（zfoo 要求定时方法以 cron 开头） */
    @Scheduler(cron = "0 * * * * ?")
    public void cronBackup() {
        Map<String, String> settings = load();
        if (!Boolean.parseBoolean(settings.get(ENABLED))) {
            return;
        }
        LocalTime time = parseClock(settings.getOrDefault(TIME, DEFAULT_TIME));
        LocalDateTime now = LocalDateTime.now();
        if (time == null || !now.toLocalTime().format(CLOCK).equals(time.format(CLOCK)) || now.toLocalDate().equals(lastScheduledDate)) {
            return;
        }
        lastScheduledDate = now.toLocalDate();
        if (!start()) {
            log.warn("定时备份跳过：上一次备份还在执行");
        }
    }

    private boolean start() {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        executor.execute(() -> {
            try {
                backup();
            } finally {
                running.set(false);
            }
        });
        return true;
    }

    private void backup() {
        long startedAt = System.currentTimeMillis();
        Path dir = Path.of(properties.getBackupDir());
        String name = "admin_background-" + LocalDateTime.now().format(FILE_TIME) + ".dump";
        Path part = dir.resolve(name + ".part");
        Path output = dir.resolve(name + ".log");
        try {
            Files.createDirectories(dir);
            URI uri = URI.create(properties.getPostgresqlUrl().substring("jdbc:".length()));
            String database = uri.getPath().replaceFirst("^/", "");
            ProcessBuilder builder = new ProcessBuilder(properties.getBackupPgDump(), "-h", uri.getHost(), "-p", String.valueOf(uri.getPort()),
                    "-U", properties.getPostgresqlUsername(), "-d", database, "-Fc", "-f", part.toString());
            builder.environment().put("PGPASSWORD", properties.getPostgresqlPassword());
            builder.redirectErrorStream(true).redirectOutput(output.toFile());
            Process process = builder.start();
            if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                finish(startedAt, false, "备份超时（超过 " + TIMEOUT_MINUTES + " 分钟）");
                return;
            }
            String text = Files.readString(output, StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0) {
                finish(startedAt, false, "pg_dump 失败：" + text);
                return;
            }
            Files.move(part, dir.resolve(name), StandardCopyOption.ATOMIC_MOVE);
            int removed = prune();
            finish(startedAt, true, name + "，" + Files.size(dir.resolve(name)) / 1024 + " KB" + (removed > 0 ? "，清理旧备份 " + removed + " 份" : ""));
        } catch (IOException exception) {
            finish(startedAt, false, "备份失败：" + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            finish(startedAt, false, "备份被中断");
        } finally {
            deleteQuietly(part);
            deleteQuietly(output);
        }
    }

    /** 按保留份数删除最旧的备份，返回删除的数量 */
    private int prune() throws IOException {
        int keep = parseKeep(load().get(KEEP));
        List<Path> files = listFiles();
        int removed = 0;
        for (Path file : files.subList(Math.min(keep, files.size()), files.size())) {
            Files.deleteIfExists(file);
            removed++;
        }
        return removed;
    }

    private void finish(long startedAt, boolean success, String message) {
        String text = message.length() > MESSAGE_MAX_LENGTH ? message.substring(0, MESSAGE_MAX_LENGTH) + "…" : message;
        if (success) {
            log.info("数据库备份完成: {}", text);
        } else {
            log.warn("数据库备份失败: {}", text);
        }
        save(Map.of(LAST_TIME, String.valueOf(startedAt), LAST_SUCCESS, String.valueOf(success), LAST_MESSAGE, text));
    }

    /** 备份文件按名称（即时间）倒序 */
    private List<Path> listFiles() throws IOException {
        Path dir = Path.of(properties.getBackupDir());
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(path -> FILE_NAME.matcher(path.getFileName().toString()).matches())
                    .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed()).toList();
        }
    }

    /** 只接受备份文件名格式，防止通过文件名访问目录外的文件 */
    private Path requireFile(String name) {
        if (name == null || !FILE_NAME.matcher(name).matches()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        Path file = Path.of(properties.getBackupDir()).resolve(name);
        if (!Files.isRegularFile(file)) {
            throw new BusinessException(ErrorCode.BACKUP_NOT_FOUND);
        }
        return file;
    }

    private BackupResponse response() {
        Map<String, String> settings = load();
        BackupResponse response = new BackupResponse();
        response.setEnabled(Boolean.parseBoolean(settings.get(ENABLED)));
        response.setTime(settings.getOrDefault(TIME, DEFAULT_TIME));
        response.setKeep(parseKeep(settings.get(KEEP)));
        response.setDir(Path.of(properties.getBackupDir()).toAbsolutePath().normalize().toString());
        response.setRunning(running.get());
        String lastTime = settings.get(LAST_TIME);
        response.setLastTime(StringUtils.isBlank(lastTime) ? null : Long.parseLong(lastTime));
        String lastSuccess = settings.get(LAST_SUCCESS);
        response.setLastSuccess(StringUtils.isBlank(lastSuccess) ? null : Boolean.parseBoolean(lastSuccess));
        response.setLastMessage(settings.getOrDefault(LAST_MESSAGE, ""));
        try {
            response.setFiles(listFiles().stream().map(BackupService::toFile).toList());
        } catch (IOException exception) {
            throw new IllegalStateException("读取备份目录失败", exception);
        }
        return response;
    }

    private static BackupResponse.BackupFile toFile(Path path) {
        try {
            return new BackupResponse.BackupFile(path.getFileName().toString(), Files.size(path), Files.getLastModifiedTime(path).toMillis());
        } catch (IOException exception) {
            throw new IllegalStateException("读取备份文件失败: " + path, exception);
        }
    }

    private static LocalTime parseClock(String time) {
        if (time == null || !time.trim().matches("^\\d{2}:\\d{2}$")) {
            return null;
        }
        try {
            return LocalTime.parse(time.trim(), CLOCK);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private static int parseKeep(String keep) {
        try {
            int value = StringUtils.isBlank(keep) ? DEFAULT_KEEP : Integer.parseInt(keep);
            return value < 1 || value > KEEP_MAX ? DEFAULT_KEEP : value;
        } catch (NumberFormatException exception) {
            return DEFAULT_KEEP;
        }
    }

    private Map<String, String> load() {
        return systemSettingMapper.selectList(Wrappers.<SystemSetting>lambdaQuery().likeRight(SystemSetting::getKey, "backup.")).stream()
                .collect(Collectors.toMap(SystemSetting::getKey, SystemSetting::getValue));
    }

    private void save(Map<String, String> values) {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, String> entry : new LinkedHashMap<>(values).entrySet()) {
            SystemSetting setting = new SystemSetting();
            setting.setKey(entry.getKey());
            setting.setValue(entry.getValue());
            setting.setUpdatedAt(now);
            if (systemSettingMapper.updateById(setting) == 0) {
                systemSettingMapper.insert(setting);
            }
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            log.warn("删除临时文件失败: {}", path);
        }
    }
}
