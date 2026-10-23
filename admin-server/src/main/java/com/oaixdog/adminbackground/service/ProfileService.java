package com.oaixdog.adminbackground.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.oaixdog.adminbackground.entity.Account;
import com.oaixdog.adminbackground.entity.Role;
import com.oaixdog.adminbackground.exception.BusinessException;
import com.oaixdog.adminbackground.exception.ErrorCode;
import com.oaixdog.adminbackground.mapper.AccountMapper;
import com.oaixdog.adminbackground.mapper.AccountRoleMapper;
import com.oaixdog.adminbackground.mapper.RoleMapper;
import com.oaixdog.adminbackground.protocol.ProfileResponse;
import com.zfoo.protocol.util.StringUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 个人设置：登录账号查看和修改自己的资料与密码，不需要菜单权限。
 */
@Service
@RequiredArgsConstructor
public class ProfileService {

    private static final int USERNAME_MAX_LENGTH = 64;
    private static final int REMARK_MAX_LENGTH = 128;
    /** 头像 data URL 最长字符数，约 200KB 图片 */
    private static final int AVATAR_MAX_LENGTH = 280_000;

    private final PermissionService permissionService;
    private final LoginService loginService;
    private final PasswordEncoder passwordEncoder;
    private final AccountMapper accountMapper;
    private final AccountRoleMapper accountRoleMapper;
    private final RoleMapper roleMapper;

    public ProfileResponse get(String authorization) {
        Account account = permissionService.requireAccount(authorization);
        List<Long> roleIds = accountRoleMapper.listRoleIds(account.getId());
        List<String> roles = roleIds.isEmpty() ? List.of() : roleMapper.selectList(Wrappers.<Role>lambdaQuery()
                .in(Role::getId, roleIds)).stream().map(Role::getName).toList();
        return ProfileResponse.valueOf(account, permissionService.isSuperAdmin(account), roles);
    }

    public ProfileResponse update(String authorization, String username, String avatar, String remark) {
        Account account = permissionService.requireAccount(authorization);
        String displayName = username == null ? "" : username.trim();
        String accountRemark = remark == null ? "" : remark.trim();
        if (StringUtils.isBlank(displayName) || displayName.length() > USERNAME_MAX_LENGTH || accountRemark.length() > REMARK_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        if (!ImageDataUrls.valid(avatar, AVATAR_MAX_LENGTH)) {
            throw new BusinessException(ErrorCode.IMAGE_INVALID);
        }
        accountMapper.update(null, Wrappers.<Account>lambdaUpdate().eq(Account::getId, account.getId())
                .set(Account::getUsername, displayName).set(Account::getAvatar, avatar == null ? "" : avatar)
                .set(Account::getRemark, accountRemark).set(Account::getUpdatedAt, System.currentTimeMillis()));
        return get(authorization);
    }

    /** 修改密码需要原密码；成功后其他会话失效，当前会话保留 */
    public void changePassword(String authorization, String oldPassword, String newPassword) {
        Account account = permissionService.requireAccount(authorization);
        if (StringUtils.isBlank(oldPassword) || StringUtils.isBlank(newPassword)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        if (!passwordEncoder.matches(oldPassword, account.getPasswordHash())) {
            throw new BusinessException(ErrorCode.PASSWORD_INCORRECT);
        }
        if (!PasswordPolicy.valid(newPassword)) {
            throw new BusinessException(ErrorCode.PASSWORD_TOO_WEAK);
        }
        accountMapper.update(null, Wrappers.<Account>lambdaUpdate().eq(Account::getId, account.getId())
                .set(Account::getPasswordHash, passwordEncoder.encode(newPassword)).set(Account::getUpdatedAt, System.currentTimeMillis()));
        loginService.invalidateOtherSessions(account.getId(), authorization);
    }
}
