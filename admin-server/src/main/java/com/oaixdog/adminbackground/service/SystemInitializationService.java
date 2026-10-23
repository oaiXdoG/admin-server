package com.oaixdog.adminbackground.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.oaixdog.adminbackground.config.ApplicationProperties;
import com.oaixdog.adminbackground.entity.Account;
import com.oaixdog.adminbackground.entity.AccountRole;
import com.oaixdog.adminbackground.entity.Role;
import com.oaixdog.adminbackground.entity.Status;
import com.oaixdog.adminbackground.mapper.AccountMapper;
import com.oaixdog.adminbackground.mapper.AccountRoleMapper;
import com.oaixdog.adminbackground.mapper.RoleMapper;
import com.zfoo.protocol.util.StringUtils;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;

/**
 * 启动初始化：
 * 1. 确保内置角色「超级管理员」存在；它拥有全部菜单，由权限判断直接放行，不写 role_menu。
 * 2. 系统里一个账号都没有时，按配置 admin.init.account / admin.init.password 创建初始超级管理员；
 *    密码未配置时随机生成并打印到日志，登录后应立即在「用户管理」里重置。
 */
@Service
@RequiredArgsConstructor
public class SystemInitializationService {

    private static final Logger log = LoggerFactory.getLogger(SystemInitializationService.class);
    private static final String PASSWORD_LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz";
    private static final String PASSWORD_DIGITS = "23456789";
    private static final String PASSWORD_CHARS = PASSWORD_LETTERS + PASSWORD_DIGITS;

    private final ApplicationProperties properties;
    private final PasswordEncoder passwordEncoder;
    private final AccountMapper accountMapper;
    private final AccountRoleMapper accountRoleMapper;
    private final RoleMapper roleMapper;

    @PostConstruct
    @Transactional
    public void initialize() {
        Role superAdmin = ensureSuperAdminRole();
        ensureInitialAdmin(superAdmin);
    }

    private Role ensureSuperAdminRole() {
        Role role = roleMapper.selectOne(Wrappers.<Role>lambdaQuery().eq(Role::getCode, Role.SUPER_ADMIN_CODE));
        if (role == null) {
            long now = System.currentTimeMillis();
            role = new Role();
            role.setName("超级管理员");
            role.setCode(Role.SUPER_ADMIN_CODE);
            role.setDescription("拥有全部菜单和按钮，不需要分配菜单");
            role.setStatus(Status.ENABLED);
            role.setBuiltIn(true);
            role.setCreatedAt(now);
            role.setUpdatedAt(now);
            roleMapper.insert(role);
        }
        return role;
    }

    private void ensureInitialAdmin(Role superAdmin) {
        if (accountMapper.selectCount(null) > 0) {
            return;
        }
        String account = StringUtils.isBlank(properties.getAdminInitAccount()) ? "admin" : properties.getAdminInitAccount().trim();
        boolean generated = StringUtils.isBlank(properties.getAdminInitPassword());
        String password = generated ? randomPassword() : properties.getAdminInitPassword();
        long now = System.currentTimeMillis();
        Account admin = new Account();
        admin.setAccount(account);
        admin.setPasswordHash(passwordEncoder.encode(password));
        admin.setUsername("管理员");
        admin.setRemark("系统初始化创建");
        admin.setStatus(Status.ENABLED);
        admin.setCreatedAt(now);
        admin.setUpdatedAt(now);
        accountMapper.insert(admin);
        AccountRole accountRole = new AccountRole();
        accountRole.setAccountId(admin.getId());
        accountRole.setRoleId(superAdmin.getId());
        accountRoleMapper.insert(accountRole);
        if (generated) {
            log.warn("已创建初始超级管理员，账号: {}，随机密码: {}。请登录后立即在「用户管理」里重置密码。", account, password);
        } else {
            log.warn("已创建初始超级管理员，账号: {}，密码为配置 admin.init.password 的值。请登录后立即重置密码。", account);
        }
    }

    /** 16 位随机密码，前两位固定为一个字母和一个数字，保证符合密码规则 */
    private static String randomPassword() {
        SecureRandom random = new SecureRandom();
        StringBuilder builder = new StringBuilder();
        builder.append(PASSWORD_LETTERS.charAt(random.nextInt(PASSWORD_LETTERS.length())));
        builder.append(PASSWORD_DIGITS.charAt(random.nextInt(PASSWORD_DIGITS.length())));
        for (int i = 0; i < 14; i++) {
            builder.append(PASSWORD_CHARS.charAt(random.nextInt(PASSWORD_CHARS.length())));
        }
        return builder.toString();
    }
}
