package com.oaixdog.adminbackground.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.oaixdog.adminbackground.entity.Account;
import com.oaixdog.adminbackground.entity.Menu;
import com.oaixdog.adminbackground.entity.Role;
import com.oaixdog.adminbackground.entity.Status;
import com.oaixdog.adminbackground.exception.BusinessException;
import com.oaixdog.adminbackground.exception.ErrorCode;
import com.oaixdog.adminbackground.mapper.AccountMapper;
import com.oaixdog.adminbackground.mapper.AccountRoleMapper;
import com.oaixdog.adminbackground.mapper.MenuMapper;
import com.oaixdog.adminbackground.mapper.RoleMapper;
import com.oaixdog.adminbackground.mapper.RoleMenuMapper;
import com.oaixdog.adminbackground.protocol.MenuResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 权限判断：账号拥有的启用角色所分配的启用菜单；超级管理员拥有全部启用菜单。
 */
@Service
@RequiredArgsConstructor
public class PermissionService {

    private final LoginService loginService;
    private final AccountMapper accountMapper;
    private final AccountRoleMapper accountRoleMapper;
    private final RoleMapper roleMapper;
    private final RoleMenuMapper roleMenuMapper;
    private final MenuMapper menuMapper;

    public Account requireAccount(String authorization) {
        Long accountId = loginService.findAccountId(authorization);
        Account account = accountId == null ? null : accountMapper.selectById(accountId);
        if (account == null || !Objects.equals(account.getStatus(), Status.ENABLED)) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        loginService.touch(authorization, account.getId());
        return account;
    }

    /** 接口入口：已登录并且拥有目标菜单（页面或按钮，按 route_name 匹配） */
    public Account requireMenu(String authorization, String routeName) {
        Account account = requireAccount(authorization);
        Long menuId = menuIdByRoute(routeName);
        if (menuId == null || !menuIds(account).contains(menuId)) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED);
        }
        return account;
    }

    /** 有其中任一菜单即可，用于多个页面共用的读取接口 */
    public Account requireAnyMenu(String authorization, String... routeNames) {
        Account account = requireAccount(authorization);
        Set<Long> owned = menuIds(account);
        for (String routeName : routeNames) {
            Long menuId = menuIdByRoute(routeName);
            if (menuId != null && owned.contains(menuId)) {
                return account;
            }
        }
        throw new BusinessException(ErrorCode.PERMISSION_DENIED);
    }

    public List<MenuResponse> listMenus(String authorization) {
        Account account = requireAccount(authorization);
        Set<Long> ids = menuIds(account);
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Menu> menus = menuMapper.selectBatchIds(ids).stream()
                .sorted(Comparator.comparing((Menu menu) -> menu.getParentId() == null ? 0L : menu.getParentId())
                        .thenComparing(menu -> menu.getSort() == null ? 0 : menu.getSort())
                        .thenComparing(Menu::getId))
                .toList();
        return MenuResponse.listOf(menus);
    }

    public boolean isSuperAdmin(Account account) {
        List<Long> roleIds = accountRoleMapper.listRoleIds(account.getId());
        return !roleIds.isEmpty() && roleMapper.selectCount(Wrappers.<Role>lambdaQuery().in(Role::getId, roleIds)
                .eq(Role::getCode, Role.SUPER_ADMIN_CODE).eq(Role::getStatus, Status.ENABLED)) > 0;
    }

    /** 账号拥有的启用菜单 */
    private Set<Long> menuIds(Account account) {
        if (isSuperAdmin(account)) {
            return new HashSet<>(menuMapper.selectList(Wrappers.<Menu>lambdaQuery().eq(Menu::getStatus, Status.ENABLED))
                    .stream().map(Menu::getId).toList());
        }
        List<Long> roleIds = enabledRoleIds(account);
        if (roleIds.isEmpty()) {
            return Set.of();
        }
        List<Long> ids = new ArrayList<>(new HashSet<>(roleMenuMapper.listMenuIds(roleIds)));
        if (ids.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(menuMapper.selectList(Wrappers.<Menu>lambdaQuery().in(Menu::getId, ids)
                .eq(Menu::getStatus, Status.ENABLED)).stream().map(Menu::getId).toList());
    }

    private List<Long> enabledRoleIds(Account account) {
        List<Long> roleIds = accountRoleMapper.listRoleIds(account.getId());
        if (roleIds.isEmpty()) {
            return List.of();
        }
        return roleMapper.selectList(Wrappers.<Role>lambdaQuery().in(Role::getId, roleIds)
                .eq(Role::getStatus, Status.ENABLED)).stream().map(Role::getId).toList();
    }

    private Long menuIdByRoute(String routeName) {
        Menu menu = menuMapper.selectOne(Wrappers.<Menu>lambdaQuery().eq(Menu::getRouteName, routeName)
                .eq(Menu::getStatus, Status.ENABLED));
        return menu == null ? null : menu.getId();
    }
}
