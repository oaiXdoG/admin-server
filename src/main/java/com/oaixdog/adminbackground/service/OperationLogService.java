package com.oaixdog.adminbackground.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.oaixdog.adminbackground.entity.Account;
import com.oaixdog.adminbackground.entity.OperationLog;
import com.oaixdog.adminbackground.exception.BusinessException;
import com.oaixdog.adminbackground.exception.ErrorCode;
import com.oaixdog.adminbackground.mapper.AccountMapper;
import com.oaixdog.adminbackground.mapper.OperationLogMapper;
import com.oaixdog.adminbackground.protocol.LoginRequest;
import com.oaixdog.adminbackground.protocol.OperationLogResponse;
import com.zfoo.net.router.attachment.HttpAttachment;
import com.zfoo.protocol.util.JsonUtils;
import com.zfoo.protocol.util.StringUtils;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.QueryStringDecoder;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 后台操作日志：登录、退出和所有写操作写入 PostgreSQL 表 operation_log，在平台页面「系统管理 → 操作日志」（manage_operation_log）查询。
 * 与系统设置里的日志存储无关，那里只用来读取外部业务服务写入 ClickHouse 的日志。
 */
@Service
@RequiredArgsConstructor
public class OperationLogService {

    private static final Logger log = LoggerFactory.getLogger(OperationLogService.class);

    private static final String MENU = "manage_operation_log";
    private static final int PAGE_SIZE_MAX = 200;
    private static final int OFFSET_MAX = 10_000;
    private static final int PARAMS_MAX_LENGTH = 8_000;
    /** 图片只记是否修改，不保存内容 */
    private static final Set<String> IMAGE_FIELDS = Set.of("logo", "favicon", "avatar");

    /** 记录的接口和操作名称，其余接口（查询类）不记录 */
    private static final Map<String, String> ACTIONS = Map.ofEntries(
            Map.entry("/api/login", "登录"),
            Map.entry("/api/logout", "退出登录"),
            Map.entry("/api/profile/update", "修改个人资料"),
            Map.entry("/api/profile/password", "修改密码"),
            Map.entry("/api/project/create", "新建项目"),
            Map.entry("/api/project/update", "修改项目"),
            Map.entry("/api/project/close", "关闭项目"),
            Map.entry("/api/project/reopen", "重新启用项目"),
            Map.entry("/api/account/create", "新建账号"),
            Map.entry("/api/account/update", "修改账号"),
            Map.entry("/api/account/status", "启用/禁用账号"),
            Map.entry("/api/account/role/update", "分配平台角色"),
            Map.entry("/api/account/project/update", "修改账号的项目关联"),
            Map.entry("/api/role/create", "新建平台角色"),
            Map.entry("/api/role/update", "修改平台角色"),
            Map.entry("/api/role/remove", "删除平台角色"),
            Map.entry("/api/role/menu/update", "分配平台角色菜单"),
            Map.entry("/api/role-template/create", "新建角色模板"),
            Map.entry("/api/role-template/update", "修改角色模板"),
            Map.entry("/api/role-template/remove", "删除角色模板"),
            Map.entry("/api/role-template/menu/update", "分配角色模板菜单"),
            Map.entry("/api/project/role/create", "新建项目角色"),
            Map.entry("/api/project/role/update", "修改项目角色"),
            Map.entry("/api/project/role/remove", "删除项目角色"),
            Map.entry("/api/project/role/menu/update", "分配项目角色菜单"),
            Map.entry("/api/project/role/import", "从模板导入项目角色"),
            Map.entry("/api/project/member/add", "添加项目成员"),
            Map.entry("/api/project/member/update", "修改成员角色"),
            Map.entry("/api/project/member/remove", "移除项目成员"),
            Map.entry("/api/menu/create", "新建菜单"),
            Map.entry("/api/menu/update", "修改菜单"),
            Map.entry("/api/menu/remove", "删除菜单"),
            Map.entry("/api/system/setting/update", "修改系统设置"),
            Map.entry("/api/system/log-storage/update", "修改日志存储配置"),
            Map.entry("/api/system/log-storage/retention", "修改日志保留期"),
            Map.entry("/api/system/backup/update", "修改备份设置"),
            Map.entry("/api/system/backup/run", "立即备份"),
            Map.entry("/api/system/backup/download", "下载备份"),
            Map.entry("/api/system/backup/remove", "删除备份"),
            Map.entry("/api/log/source/save", "修改日志源"),
            Map.entry("/api/log/event/dict/save", "修改事件说明"),
            Map.entry("/api/log/event/dict/remove", "删除事件说明"),
            Map.entry("/api/log/export", "导出日志"),
            Map.entry("/api/server/host/create", "新增服务器"),
            Map.entry("/api/server/host/update", "修改服务器"),
            Map.entry("/api/server/host/remove", "删除服务器"),
            Map.entry("/api/server/host/install", "生成服务器安装命令"),
            Map.entry("/api/server/process/create", "新增托管进程"),
            Map.entry("/api/server/process/update", "修改托管进程"),
            Map.entry("/api/server/process/remove", "删除托管进程"),
            Map.entry("/api/server/process/operate", "启动/停止/重启进程"),
            Map.entry("/api/server/template/save", "保存 JVM 参数模板"),
            Map.entry("/api/server/template/remove", "删除 JVM 参数模板"),
            Map.entry("/api/server/process-type/save", "保存进程类型"),
            Map.entry("/api/server/process-type/remove", "删除进程类型"),
            Map.entry("/api/server/report-config/reset", "重置上报密钥"),
            Map.entry("/api/server/process/deploy", "部署进程"),
            Map.entry("/api/server/process/file/request", "进程文件操作"),
            Map.entry("/api/data/source/create", "新建数据源"),
            Map.entry("/api/data/source/update", "修改数据源"),
            Map.entry("/api/data/source/remove", "删除数据源"),
            Map.entry("/api/data/template/create", "新建数据查询模板"),
            Map.entry("/api/data/template/update", "修改数据查询模板"),
            Map.entry("/api/data/template/remove", "删除数据查询模板"),
            Map.entry("/api/data/query/export", "导出数据查询结果"));

    private final LoginService loginService;
    private final AccountMapper accountMapper;
    private final PermissionService permissionService;
    private final OperationLogMapper operationLogMapper;

    /** 处理请求前确定的操作信息；操作者要在处理前取，退出登录之后会话就没了 */
    public record Operation(String route, String action, Long accountId, String account) {
    }

    /** 不需要记录时返回 null */
    public Operation start(HttpAttachment attachment) {
        String route = new QueryStringDecoder(attachment.getFullHttpRequest().uri()).path();
        String action = ACTIONS.get(route);
        if (action == null) {
            return null;
        }
        Long accountId = loginService.findAccountId(attachment.getFullHttpRequest().headers().get(HttpHeaderNames.AUTHORIZATION));
        Account account = accountId == null ? null : accountMapper.selectById(accountId);
        return new Operation(route, action, accountId, account == null ? "" : account.getAccount());
    }

    /** code 为 0 表示成功，否则是业务错误码；记录失败只打印告警，不影响业务请求 */
    public void finish(Operation operation, Object request, int code) {
        if (operation == null) {
            return;
        }
        try {
            String account = operation.account();
            Long accountId = operation.accountId();
            // 登录前还没有会话，用请求里的登录名；登录成功时再查出账号 ID
            if (request instanceof LoginRequest login && StringUtils.isNotBlank(login.getAccount())) {
                account = login.getAccount().trim();
                Account matched = code == 0 ? accountMapper.selectOne(Wrappers.<Account>lambdaQuery().eq(Account::getAccount, account)) : null;
                accountId = matched == null ? null : matched.getId();
            }
            String params = JsonUtils.object2String(params(request));
            OperationLog entry = new OperationLog();
            entry.setAccountId(accountId);
            entry.setAccount(account.length() > 64 ? account.substring(0, 64) : account);
            entry.setRoute(operation.route());
            entry.setAction(operation.action());
            entry.setSuccess(code == 0);
            entry.setCode(code);
            entry.setParams(params.length() > PARAMS_MAX_LENGTH ? params.substring(0, PARAMS_MAX_LENGTH) : params);
            entry.setCreatedAt(System.currentTimeMillis());
            operationLogMapper.insert(entry);
        } catch (RuntimeException exception) {
            log.warn("记录操作日志失败: {}", exception.getMessage());
        }
    }

    /** 名称含 password 或 secret 的字段记为 ******，图片字段记为 (图片)，文件内容记为 (文件内容) */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> params(Object request) {
        if (request == null) {
            return Map.of();
        }
        Map<String, Object> params = JsonUtils.string2Object(JsonUtils.object2String(request), LinkedHashMap.class);
        params.replaceAll((key, value) -> {
            String lower = key.toLowerCase();
            if (lower.contains("password") || lower.contains("secret")) {
                return value == null || "".equals(value) ? "" : "******";
            }
            if (IMAGE_FIELDS.contains(key)) {
                return value == null || "".equals(value) ? "" : "(图片)";
            }
            if ("content".equals(key)) {
                return value == null || "".equals(value) ? "" : "(文件内容)";
            }
            return value;
        });
        return params;
    }

    /**
     * 查询操作日志，时间范围必填。account 精确匹配登录名，action 按操作名称模糊匹配，success 为空表示不限。
     */
    public OperationLogResponse list(String authorization, Long startTime, Long endTime, String account, String action,
                                     Boolean success, Integer page, Integer pageSize) {
        permissionService.requirePlatformMenu(authorization, MENU);
        int pageNumber = page == null ? 1 : page;
        int size = pageSize == null ? 20 : pageSize;
        if (startTime == null || endTime == null || startTime >= endTime || pageNumber < 1 || size < 1 || size > PAGE_SIZE_MAX
                || (long) (pageNumber - 1) * size > OFFSET_MAX) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        LambdaQueryWrapper<OperationLog> query = Wrappers.<OperationLog>lambdaQuery()
                .ge(OperationLog::getCreatedAt, startTime).lt(OperationLog::getCreatedAt, endTime)
                .eq(StringUtils.isNotBlank(account), OperationLog::getAccount, account == null ? "" : account.trim())
                .like(StringUtils.isNotBlank(action), OperationLog::getAction, action == null ? "" : action.trim())
                .eq(success != null, OperationLog::getSuccess, success);
        OperationLogResponse response = new OperationLogResponse();
        response.setTotal(operationLogMapper.selectCount(query));
        // 分页参数已校验为整数
        List<OperationLog> rows = operationLogMapper.selectList(query.orderByDesc(OperationLog::getId)
                .last("limit " + size + " offset " + (pageNumber - 1) * size));
        response.setList(rows.stream().map(OperationLogService::toRow).toList());
        return response;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toRow(OperationLog entry) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", entry.getId());
        result.put("time", entry.getCreatedAt());
        result.put("accountId", entry.getAccountId());
        result.put("account", entry.getAccount());
        result.put("action", entry.getAction());
        result.put("route", entry.getRoute());
        result.put("success", entry.getSuccess());
        result.put("code", entry.getCode());
        Object params;
        try {
            params = JsonUtils.string2Object(entry.getParams(), LinkedHashMap.class);
        } catch (RuntimeException exception) {
            params = Map.of("raw", entry.getParams());
        }
        result.put("params", params);
        return result;
    }
}
