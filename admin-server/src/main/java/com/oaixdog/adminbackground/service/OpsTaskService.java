package com.oaixdog.adminbackground.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.oaixdog.adminbackground.entity.Account;
import com.oaixdog.adminbackground.entity.OpsTask;
import com.oaixdog.adminbackground.exception.BusinessException;
import com.oaixdog.adminbackground.exception.ErrorCode;
import com.oaixdog.adminbackground.mapper.OpsTaskMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 启停、发布都在后台线程里通过 SSH 执行，请求立即返回任务 id。
 * 执行中的输出在内存里，发起操作的页面按 offset 增量读取；结束后输出写入 ops_task。
 * 谁做了什么、成功还是失败，在任务结束时写入操作日志。同一个程序同一时间只能有一个任务。
 * 管理后台重启时，还在执行中的任务记为失败。
 * <p>
 * 查看输出需要 ops_app。
 */
@Service
@RequiredArgsConstructor
public class OpsTaskService implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(OpsTaskService.class);

    private static final String APP_MENU = "ops_app";
    /** 输出最多保留这么多字符，超过时丢掉前面的 */
    private static final int OUTPUT_MAX_CHARS = 200_000;

    private final PermissionService permissionService;
    private final OperationLogService operationLogService;
    private final OpsTaskMapper opsTaskMapper;

    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(
            Thread.ofPlatform().name("ops-task-", 0).daemon(true).factory());
    private final Map<Long, Running> running = new ConcurrentHashMap<>();
    private final Set<String> busy = ConcurrentHashMap.newKeySet();

    /** 任务的执行内容：输出交给 out，返回是否成功；抛出的异常算失败，message 写入输出 */
    public interface Work {
        boolean run(Consumer<String> out) throws Exception;
    }

    private static final class Running {
        private final StringBuilder output = new StringBuilder();
        private int dropped;

        private synchronized void append(String line) {
            output.append(line).append('\n');
            if (output.length() > OUTPUT_MAX_CHARS) {
                int cut = output.length() - OUTPUT_MAX_CHARS;
                output.delete(0, cut);
                dropped += cut;
            }
        }

        /** offset 为整个输出（含已丢弃部分）的位置 */
        private synchronized String from(int offset) {
            int start = Math.max(0, offset - dropped);
            return start >= output.length() ? "" : output.substring(start);
        }

        private synchronized int end() {
            return dropped + output.length();
        }

        private synchronized String all() {
            return dropped > 0 ? "...（前面的输出过长已省略）\n" + output : output.toString();
        }
    }

    @Override
    public void afterPropertiesSet() {
        List<OpsTask> interrupted = opsTaskMapper.selectList(Wrappers.<OpsTask>lambdaQuery().eq(OpsTask::getStatus, OpsTask.RUNNING));
        if (interrupted.isEmpty()) {
            return;
        }
        var failed = new OpsTask();
        failed.setStatus(OpsTask.FAILED);
        failed.setFinishedAt(System.currentTimeMillis());
        opsTaskMapper.update(failed, Wrappers.<OpsTask>lambdaUpdate().eq(OpsTask::getStatus, OpsTask.RUNNING));
        for (OpsTask task : interrupted) {
            record(null, task.getAccount(), task.getType(), task.getTitle(), task.getAppId(), false);
        }
        log.warn("管理后台重启前有 {} 个操作没有执行完，已记为失败", interrupted.size());
    }

    /**
     * 提交任务并立即返回任务 id。
     *
     * @param lock 互斥的对象，例如 app:3；同一个 lock 已有任务在执行时抛出 OPS_TASK_BUSY
     */
    public long submit(Account account, String type, String title, long appId, String lock, Work work) {
        if (!busy.add(lock)) {
            throw new BusinessException(ErrorCode.OPS_TASK_BUSY);
        }
        var task = new OpsTask();
        task.setType(type);
        task.setTitle(title.length() > 256 ? title.substring(0, 256) : title);
        task.setAppId(appId);
        task.setAccount(account == null ? "" : account.getAccount());
        task.setStatus(OpsTask.RUNNING);
        task.setOutput("");
        task.setStartedAt(System.currentTimeMillis());
        task.setFinishedAt(0L);
        try {
            opsTaskMapper.insert(task);
        } catch (RuntimeException e) {
            busy.remove(lock);
            throw e;
        }
        long id = task.getId();
        Running state = new Running();
        running.put(id, state);
        executor.execute(() -> {
            boolean success = false;
            try {
                success = work.run(state::append);
            } catch (BusinessException e) {
                state.append(e.getMessage());
            } catch (Exception e) {
                log.error("运维任务出错，id={}，{}", id, title, e);
                state.append("出错：" + e.getMessage());
            } finally {
                var finished = new OpsTask();
                finished.setId(id);
                finished.setStatus(success ? OpsTask.SUCCESS : OpsTask.FAILED);
                finished.setOutput(state.all());
                finished.setFinishedAt(System.currentTimeMillis());
                opsTaskMapper.updateById(finished);
                running.remove(id);
                busy.remove(lock);
                record(account, account == null ? "" : account.getAccount(), type, title, appId, success);
                log.info("操作结束，id={}，{}，结果={}", id, title, finished.getStatus());
            }
        });
        return id;
    }

    /** 操作日志里的结果。title 放进参数，操作列用固定名称，方便按「发布」「回滚」筛选 */
    private void record(Account account, String accountName, String type, String title, long appId, boolean success) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("title", title);
        if (appId > 0) {
            params.put("appId", appId);
        }
        operationLogService.record(account == null ? null : account.getId(), accountName, routeOf(type), actionOf(type), params,
                success, success ? 0 : ErrorCode.OPS_SSH_FAILED.getCode());
    }

    private static String routeOf(String type) {
        return switch (type) {
            case "deploy" -> "/ops/app/deploy";
            case "start", "stop", "restart" -> "/ops/app/control";
            default -> "/ops/app/control";
        };
    }

    private static String actionOf(String type) {
        return switch (type) {
            case "start" -> "启动程序";
            case "stop" -> "停止程序";
            case "restart" -> "重启程序";
            case "deploy" -> "发布程序";
            default -> "运维操作";
        };
    }

    /** 任务详情；output 为 offset 之后的输出，下次用返回的 offset 继续读 */
    public Map<String, Object> get(String authorization, Long taskId, Integer offset) {
        permissionService.requireMenu(authorization, APP_MENU);
        OpsTask task = taskId == null ? null : opsTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.OPS_TASK_NOT_FOUND);
        }
        int from = offset == null || offset < 0 ? 0 : offset;
        Map<String, Object> result = new HashMap<>(summary(task));
        Running state = running.get(taskId);
        if (state != null) {
            result.put("output", state.from(from));
            result.put("offset", state.end());
        } else {
            String output = task.getOutput() == null ? "" : task.getOutput();
            result.put("output", from >= output.length() ? "" : output.substring(from));
            result.put("offset", output.length());
        }
        return result;
    }

    /** 程序当前是否有任务在执行 */
    public boolean isBusy(String lock) {
        return busy.contains(lock);
    }

    private static Map<String, Object> summary(OpsTask task) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", task.getId());
        map.put("type", task.getType());
        map.put("title", task.getTitle());
        map.put("appId", task.getAppId());
        map.put("account", task.getAccount());
        map.put("status", task.getStatus());
        map.put("startedAt", task.getStartedAt());
        map.put("finishedAt", task.getFinishedAt());
        return map;
    }
}
