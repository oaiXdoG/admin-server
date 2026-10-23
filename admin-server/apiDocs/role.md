# 角色

需要菜单 `manage_role`。角色只有一种，通过 `account_role` 分配给账号（一个账号可以有多个角色），可以分配全部菜单。内置「超级管理员」（`SUPER_ADMIN`）不能修改、删除，它不写 `role_menu`，由代码直接拥有全部启用菜单。

## 接口

| 接口 | 请求体 | 权限 | 说明 |
| --- | --- | --- | --- |
| `/role/list` | `{}` | `manage_role` | 返回全部角色，按 ID 排序 |
| `/role/create` | `{"name","code","description","status"}` | `manage_role:create` | 返回新角色 |
| `/role/update` | `{"roleId","name","code","description","status"}` | `manage_role:update` | 返回修改后的角色 |
| `/role/remove` | `{"roleId"}` | `manage_role:remove` | 角色仍被账号使用时拒绝 |
| `/role/menu/list` | `{"roleId"}` | `manage_role` | 返回全部菜单 `menus` 和已分配的 `menuIds` |
| `/role/menu/update` | `{"roleId","menuIds"}` | `manage_role:menu` | 整体替换角色菜单 |

## 字段规则

- `name` 必填最长 32，`code` 只允许大写字母、数字和下划线并以字母开头，最长 64；二者全表唯一。
- `description` 最长 128，`status` 为 1 启用或 2 停用。停用的角色不再提供菜单权限。
- 分配菜单：菜单必须存在，并且每个子项的上级也在提交列表中，否则返回 `100022`。

角色返回字段：`id`、`name`、`code`、`description`、`status`、`builtIn`、`createdAt`。

错误：`100001` 参数不合法，`100003` 未登录，`100016` 无权访问，`100018` 角色不存在，`100019` 角色名称已存在，`100020` 内置角色不能修改或删除，`100021` 角色正在使用，`100022` 菜单分配不合法，`100023` 角色编码已存在。

## 下拉选项

`/account/role/options`，请求体 `{}`，需要菜单 `manage_user`。只返回启用的角色，用于给账号分配角色。
