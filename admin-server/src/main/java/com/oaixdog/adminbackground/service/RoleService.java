package com.oaixdog.adminbackground.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.oaixdog.adminbackground.entity.AccountRole;
import com.oaixdog.adminbackground.entity.Menu;
import com.oaixdog.adminbackground.entity.Role;
import com.oaixdog.adminbackground.entity.RoleMenu;
import com.oaixdog.adminbackground.entity.Status;
import com.oaixdog.adminbackground.exception.BusinessException;
import com.oaixdog.adminbackground.exception.ErrorCode;
import com.oaixdog.adminbackground.mapper.AccountRoleMapper;
import com.oaixdog.adminbackground.mapper.MenuMapper;
import com.oaixdog.adminbackground.mapper.RoleMapper;
import com.oaixdog.adminbackground.mapper.RoleMenuMapper;
import com.oaixdog.adminbackground.protocol.MenuResponse;
import com.oaixdog.adminbackground.protocol.RoleMenuListResponse;
import com.oaixdog.adminbackground.protocol.RoleResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 角色的增删改查和菜单分配。角色通过 account_role 分配给账号，一个账号可以有多个角色；内置的超级管理员不能修改。
 */
@Service
@RequiredArgsConstructor
public class RoleService {

    private static final int NAME_MAX_LENGTH = 32;
    private static final int DESCRIPTION_MAX_LENGTH = 128;
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    private final PermissionService permissionService;
    private final RoleMapper roleMapper;
    private final MenuMapper menuMapper;
    private final RoleMenuMapper roleMenuMapper;
    private final AccountRoleMapper accountRoleMapper;

    public List<RoleResponse> list(String authorization) {
        permissionService.requireMenu(authorization, "manage_role");
        return roleMapper.selectList(Wrappers.<Role>lambdaQuery().orderByAsc(Role::getId)).stream().map(RoleResponse::valueOf).toList();
    }

    public RoleResponse create(String authorization, String name, String code, String description, Integer status) {
        permissionService.requireMenu(authorization, "manage_role:create");
        RoleValues values = validate(name, code, description, status);
        requireUnique(null, values);
        long now = System.currentTimeMillis();
        Role role = new Role();
        role.setName(values.name());
        role.setCode(values.code());
        role.setDescription(values.description());
        role.setStatus(status);
        role.setBuiltIn(false);
        role.setCreatedAt(now);
        role.setUpdatedAt(now);
        roleMapper.insert(role);
        return RoleResponse.valueOf(role);
    }

    public RoleResponse update(String authorization, Long roleId, String name, String code, String description, Integer status) {
        permissionService.requireMenu(authorization, "manage_role:update");
        Role role = requireEditableRole(roleId);
        RoleValues values = validate(name, code, description, status);
        requireUnique(roleId, values);
        role.setName(values.name());
        role.setCode(values.code());
        role.setDescription(values.description());
        role.setStatus(status);
        role.setUpdatedAt(System.currentTimeMillis());
        roleMapper.updateById(role);
        return RoleResponse.valueOf(role);
    }

    @Transactional
    public void remove(String authorization, Long roleId) {
        permissionService.requireMenu(authorization, "manage_role:remove");
        requireEditableRole(roleId);
        if (accountRoleMapper.selectCount(Wrappers.<AccountRole>lambdaQuery().eq(AccountRole::getRoleId, roleId)) > 0) {
            throw new BusinessException(ErrorCode.ROLE_IN_USE);
        }
        roleMenuMapper.delete(Wrappers.<RoleMenu>lambdaQuery().eq(RoleMenu::getRoleId, roleId));
        roleMapper.deleteById(roleId);
    }

    /** 返回全部菜单和该角色已分配的菜单 */
    public RoleMenuListResponse listMenus(String authorization, Long roleId) {
        permissionService.requireMenu(authorization, "manage_role");
        requireRole(roleId);
        List<Menu> menus = menuMapper.selectList(Wrappers.<Menu>lambdaQuery()).stream()
                .sorted(Comparator.comparing((Menu menu) -> menu.getParentId() == null ? 0L : menu.getParentId())
                        .thenComparing(menu -> menu.getSort() == null ? 0 : menu.getSort())
                        .thenComparing(Menu::getId)).toList();
        return RoleMenuListResponse.valueOf(menus.stream().map(MenuResponse::valueOf).toList(), roleMenuMapper.listMenuIds(List.of(roleId)));
    }

    @Transactional
    public void updateMenus(String authorization, Long roleId, List<Long> menuIds) {
        permissionService.requireMenu(authorization, "manage_role:menu");
        requireEditableRole(roleId);
        if (menuIds == null || menuIds.stream().anyMatch(Objects::isNull)) {
            throw new BusinessException(ErrorCode.ROLE_MENU_INVALID);
        }
        Set<Long> assigned = new HashSet<>(menuIds);
        List<Menu> menus = assigned.isEmpty() ? List.of() : menuMapper.selectBatchIds(assigned);
        // 菜单必须存在，并且上级目录要一起分配，否则侧栏无法从目录生成这些菜单
        if (menus.size() != assigned.size()
                || menus.stream().anyMatch(menu -> menu.getParentId() != null && !assigned.contains(menu.getParentId()))) {
            throw new BusinessException(ErrorCode.ROLE_MENU_INVALID);
        }
        roleMenuMapper.delete(Wrappers.<RoleMenu>lambdaQuery().eq(RoleMenu::getRoleId, roleId));
        if (!assigned.isEmpty()) {
            roleMenuMapper.insertBatch(roleId, assigned);
        }
    }

    /** 给账号分配角色时的下拉选项：只返回启用的角色 */
    public List<RoleResponse> options(String authorization) {
        permissionService.requireMenu(authorization, "manage_user");
        return roleMapper.selectList(Wrappers.<Role>lambdaQuery().eq(Role::getStatus, Status.ENABLED).orderByAsc(Role::getId))
                .stream().map(RoleResponse::valueOf).toList();
    }

    private Role requireRole(Long roleId) {
        Role role = roleId == null ? null : roleMapper.selectById(roleId);
        if (role == null) {
            throw new BusinessException(ErrorCode.ROLE_NOT_FOUND);
        }
        return role;
    }

    private Role requireEditableRole(Long roleId) {
        Role role = requireRole(roleId);
        if (Boolean.TRUE.equals(role.getBuiltIn())) {
            throw new BusinessException(ErrorCode.BUILT_IN_ROLE_FORBIDDEN);
        }
        return role;
    }

    private void requireUnique(Long excludeId, RoleValues values) {
        if (roleMapper.selectCount(Wrappers.<Role>lambdaQuery().eq(Role::getName, values.name()).ne(excludeId != null, Role::getId, excludeId)) > 0) {
            throw new BusinessException(ErrorCode.ROLE_NAME_EXISTS);
        }
        if (roleMapper.selectCount(Wrappers.<Role>lambdaQuery().eq(Role::getCode, values.code()).ne(excludeId != null, Role::getId, excludeId)) > 0) {
            throw new BusinessException(ErrorCode.ROLE_CODE_EXISTS);
        }
    }

    private RoleValues validate(String name, String code, String description, Integer status) {
        String roleName = name == null ? "" : name.trim();
        String roleCode = code == null ? "" : code.trim();
        String roleDescription = description == null ? "" : description.trim();
        if (roleName.isBlank() || roleName.length() > NAME_MAX_LENGTH || !CODE_PATTERN.matcher(roleCode).matches()
                || roleDescription.length() > DESCRIPTION_MAX_LENGTH || !Status.valid(status)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        return new RoleValues(roleName, roleCode, roleDescription);
    }

    private record RoleValues(String name, String code, String description) {
    }
}
