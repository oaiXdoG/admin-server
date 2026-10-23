package com.oaixdog.adminbackground.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.oaixdog.adminbackground.entity.Account;
import com.oaixdog.adminbackground.entity.AccountRole;
import com.oaixdog.adminbackground.entity.Role;
import com.oaixdog.adminbackground.entity.Status;
import com.oaixdog.adminbackground.exception.BusinessException;
import com.oaixdog.adminbackground.exception.ErrorCode;
import com.oaixdog.adminbackground.mapper.AccountMapper;
import com.oaixdog.adminbackground.mapper.AccountRoleMapper;
import com.oaixdog.adminbackground.mapper.RoleMapper;
import com.oaixdog.adminbackground.protocol.AccountCreateResponse;
import com.oaixdog.adminbackground.protocol.AccountResponse;
import com.zfoo.protocol.util.StringUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 用户管理：账号只在这里创建；角色写入 account_role。
 */
@Service
@RequiredArgsConstructor
public class AccountCommandService {

    private static final String MENU = "manage_user";
    private static final int ACCOUNT_MAX_LENGTH = 64;
    private static final int USERNAME_MAX_LENGTH = 64;
    private static final int REMARK_MAX_LENGTH = 128;

    private final PermissionService permissionService;
    private final LoginService loginService;
    private final PasswordEncoder passwordEncoder;
    private final AccountMapper accountMapper;
    private final AccountRoleMapper accountRoleMapper;
    private final RoleMapper roleMapper;

    public List<AccountResponse> list(String authorization) {
        permissionService.requireMenu(authorization, MENU);
        Map<Long, Role> roles = roleMapper.selectList(null).stream().collect(Collectors.toMap(Role::getId, Function.identity()));
        Map<Long, List<Role>> rolesByAccount = accountRoleMapper.selectList(null).stream()
                .filter(row -> roles.containsKey(row.getRoleId()))
                .collect(Collectors.groupingBy(AccountRole::getAccountId, Collectors.mapping(row -> roles.get(row.getRoleId()), Collectors.toList())));
        return accountMapper.selectList(Wrappers.<Account>lambdaQuery().orderByAsc(Account::getId)).stream()
                .map(account -> AccountResponse.valueOf(account, rolesByAccount.getOrDefault(account.getId(), List.of())))
                .toList();
    }

    public AccountCreateResponse create(String authorization, String account, String password, String username, String remark) {
        permissionService.requireMenu(authorization, MENU + ":create");
        String loginAccount = account == null ? "" : account.trim();
        String displayName = username == null ? "" : username.trim();
        String accountRemark = remark == null ? "" : remark.trim();
        if (StringUtils.isBlank(loginAccount) || loginAccount.length() > ACCOUNT_MAX_LENGTH
                || StringUtils.isBlank(displayName) || displayName.length() > USERNAME_MAX_LENGTH || accountRemark.length() > REMARK_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        if (!PasswordPolicy.valid(password)) {
            throw new BusinessException(ErrorCode.PASSWORD_TOO_WEAK);
        }
        if (accountMapper.selectCount(Wrappers.<Account>lambdaQuery().eq(Account::getAccount, loginAccount)) > 0) {
            throw new BusinessException(ErrorCode.ACCOUNT_EXISTS);
        }
        long now = System.currentTimeMillis();
        Account created = new Account();
        created.setAccount(loginAccount);
        created.setPasswordHash(passwordEncoder.encode(password));
        created.setUsername(displayName);
        created.setRemark(accountRemark);
        created.setStatus(Status.ENABLED);
        created.setCreatedAt(now);
        created.setUpdatedAt(now);
        accountMapper.insert(created);
        return AccountCreateResponse.valueOf(created.getId(), created.getAccount(), created.getUsername());
    }

    /** 修改显示名和备注；提交密码时重置密码，并让该账号已登录的会话失效 */
    public void update(String authorization, Long accountId, String username, String password, String remark) {
        permissionService.requireMenu(authorization, MENU + ":update");
        requireAccount(accountId);
        String displayName = username == null ? "" : username.trim();
        String accountRemark = remark == null ? "" : remark.trim();
        if (StringUtils.isBlank(displayName) || displayName.length() > USERNAME_MAX_LENGTH || accountRemark.length() > REMARK_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        if (password != null && !PasswordPolicy.valid(password)) {
            throw new BusinessException(ErrorCode.PASSWORD_TOO_WEAK);
        }
        Account updated = new Account();
        updated.setId(accountId);
        updated.setUsername(displayName);
        updated.setRemark(accountRemark);
        updated.setUpdatedAt(System.currentTimeMillis());
        if (password != null) {
            updated.setPasswordHash(passwordEncoder.encode(password));
        }
        accountMapper.updateById(updated);
        if (password != null) {
            loginService.invalidateAccount(accountId);
        }
    }

    /** 启用或禁用账号；禁用后已登录的会话立即失效。不能修改自己的状态 */
    public void updateStatus(String authorization, Long accountId, Integer status) {
        Account operator = permissionService.requireMenu(authorization, MENU + ":status");
        requireAccount(accountId);
        if (!Status.valid(status)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        if (Objects.equals(operator.getId(), accountId)) {
            throw new BusinessException(ErrorCode.SELF_OPERATION_FORBIDDEN);
        }
        accountMapper.update(null, Wrappers.<Account>lambdaUpdate().eq(Account::getId, accountId)
                .set(Account::getStatus, status).set(Account::getUpdatedAt, System.currentTimeMillis()));
        if (status == Status.DISABLED) {
            loginService.invalidateAccount(accountId);
        }
    }

    /**
     * 整体替换账号的角色。分配或撤销「超级管理员」只能由超级管理员操作，并且不能撤销自己的超级管理员。
     */
    @Transactional
    public void updateRoles(String authorization, Long accountId, List<Long> roleIds) {
        Account operator = permissionService.requireMenu(authorization, MENU + ":role");
        requireAccount(accountId);
        if (roleIds == null || roleIds.stream().anyMatch(Objects::isNull)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        Set<Long> target = new HashSet<>(roleIds);
        Map<Long, Role> roles = roleMapper.selectList(null).stream().collect(Collectors.toMap(Role::getId, Function.identity()));
        if (!roles.keySet().containsAll(target)) {
            throw new BusinessException(ErrorCode.ROLE_NOT_FOUND);
        }
        Set<Long> current = new HashSet<>(accountRoleMapper.listRoleIds(accountId));
        Long superAdminId = roles.values().stream().filter(role -> Role.SUPER_ADMIN_CODE.equals(role.getCode()))
                .map(Role::getId).findFirst().orElse(null);
        boolean superAdminChanged = superAdminId != null && current.contains(superAdminId) != target.contains(superAdminId);
        if (superAdminChanged && !permissionService.isSuperAdmin(operator)) {
            throw new BusinessException(ErrorCode.SUPER_ADMIN_REQUIRED);
        }
        if (superAdminChanged && Objects.equals(operator.getId(), accountId)) {
            throw new BusinessException(ErrorCode.SELF_OPERATION_FORBIDDEN);
        }
        accountRoleMapper.delete(Wrappers.<AccountRole>lambdaQuery().eq(AccountRole::getAccountId, accountId));
        for (Long roleId : target) {
            AccountRole accountRole = new AccountRole();
            accountRole.setAccountId(accountId);
            accountRole.setRoleId(roleId);
            accountRoleMapper.insert(accountRole);
        }
    }

    private void requireAccount(Long accountId) {
        if (accountId == null || accountId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        if (accountMapper.selectById(accountId) == null) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND);
        }
    }
}
