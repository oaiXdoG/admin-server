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
    /**
     * 这些接口立刻返回，远程命令还在后台跑。请求成功时不记日志，等任务结束再记真实结果。
     * 请求当时就失败（参数不合法、已有操作在执行）仍在当时记录。
     */
    private static final Set<String> DEFERRED = Set.of(
            "/ops/host/init",
            "/ops/app/control",
            "/ops/app/config/save",
            "/ops/app/deploy",
            "/ops/app/rollback");

    /** 记录的接口和操作名称，其余接口（查询类）不记录 */
    private static final Map<String, String> ACTIONS = Map.ofEntries(
            Map.entry("/login", "登录"),
            Map.entry("/logout", "退出登录"),
            Map.entry("/profile/update", "修改个人资料"),
            Map.entry("/profile/password", "修改密码"),
            Map.entry("/account/create", "新建账号"),
            Map.entry("/account/update", "修改账号"),
            Map.entry("/account/status", "启用/禁用账号"),
            Map.entry("/account/role/update", "分配角色"),
            Map.entry("/role/create", "新建角色"),
            Map.entry("/role/update", "修改角色"),
            Map.entry("/role/remove", "删除角色"),
            Map.entry("/role/menu/update", "分配角色菜单"),
            Map.entry("/menu/create", "新建菜单"),
            Map.entry("/menu/update", "修改菜单"),
            Map.entry("/menu/remove", "删除菜单"),
            Map.entry("/system/setting/update", "修改系统设置"),
            Map.entry("/system/backup/update", "修改备份设置"),
            Map.entry("/system/backup/run", "立即备份"),
            Map.entry("/system/backup/download", "下载备份"),
            Map.entry("/system/backup/remove", "删除备份"),
            Map.entry("/data/source/create", "新建数据源"),
            Map.entry("/data/source/update", "修改数据源"),
            Map.entry("/data/source/remove", "删除数据源"),
            Map.entry("/data/template/create", "新建数据查询模板"),
            Map.entry("/data/template/update", "修改数据查询模板"),
            Map.entry("/data/template/remove", "删除数据查询模板"),
            Map.entry("/data/query/export", "导出数据查询结果"),
            Map.entry("/log/event/create", "新建日志事件"),
            Map.entry("/log/event/update", "修改日志事件"),
            Map.entry("/ops/host/create", "添加主机"),
            Map.entry("/ops/host/update", "修改主机"),
            Map.entry("/ops/host/init", "初始化主机"),
            Map.entry("/ops/host/remove", "删除主机"),
            Map.entry("/ops/app/create", "新建程序"),
            Map.entry("/ops/app/update", "修改程序"),
            Map.entry("/ops/app/remove", "删除程序"),
            Map.entry("/ops/app/control", "启停程序"),
            Map.entry("/ops/app/config/save", "修改程序配置文件"),
            Map.entry("/ops/app/deploy", "发布程序"),
            Map.entry("/ops/app/rollback", "回滚程序"));

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
            if (code == 0 && DEFERRED.contains(operation.route())) {
                return;
            }
            write(accountId, account, operation.route(), operation.action(), params(request), code == 0, code);
        } catch (RuntimeException exception) {
            log.warn("记录操作日志失败: {}", exception.getMessage());
        }
    }

    /** 后台操作结束时写入。成功与否是远程命令的结果，不是接口有没有接住这次请求 */
    public void record(Long accountId, String account, String route, String action, Map<String, Object> params,
                       boolean success, int code) {
        try {
            write(accountId, account == null ? "" : account, route, action, params == null ? Map.of() : params, success, code);
        } catch (RuntimeException exception) {
            log.warn("记录操作日志失败: {}", exception.getMessage());
        }
    }

    private void write(Long accountId, String account, String route, String action, Map<String, Object> params,
                       boolean success, int code) {
        String paramsText = JsonUtils.object2String(params);
        OperationLog entry = new OperationLog();
        entry.setAccountId(accountId);
        entry.setAccount(account.length() > 64 ? account.substring(0, 64) : account);
        entry.setRoute(route);
        entry.setAction(action.length() > 64 ? action.substring(0, 64) : action);
        entry.setSuccess(success);
        entry.setCode(code);
        entry.setParams(paramsText.length() > PARAMS_MAX_LENGTH ? paramsText.substring(0, PARAMS_MAX_LENGTH) : paramsText);
        entry.setCreatedAt(System.currentTimeMillis());
        operationLogMapper.insert(entry);
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
        permissionService.requireMenu(authorization, MENU);
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
