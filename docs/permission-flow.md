# 权限执行顺序

完整设计见 `docs/permission-redesign.md`。

## 角色

一套部署只服务一个项目，角色只有一种：通过 `account_role` 分配给账号（一个账号可以有多个角色），通过 `role_menu` 获得菜单，可以分配全部菜单（子项的上级必须一起分配）。

内置角色「超级管理员」（`SUPER_ADMIN`）由代码直接放行，拥有全部启用菜单（含按钮），不写 `role_menu`。

## 流程

1. 登录校验账号状态和 bcrypt 密码，建立会话令牌。
2. 客户端调用 `/access/menu`（请求体 `{}`），得到账号拥有的启用菜单（含按钮）。
3. 菜单 = 账号的启用角色关联的启用菜单；超级管理员为全部启用菜单。
4. 接口调用 `PermissionService.requireMenu(authorization, routeName)`；多个页面共用的读取接口调用 `requireAnyMenu(authorization, routeNames...)`，有其中任一即可；只需登录的接口调用 `requireAccount(authorization)`。
5. 写接口校验页面下的按钮（菜单类型 3），按钮的 `route_name` 是权限标识，同样通过上面的方法校验；查看类接口只校验页面。客户端从 `/access/menu` 返回的按钮得到权限标识，用来隐藏没有权限的按钮。
6. 菜单或角色停用后立即失去对应权限。

## 接口权限

| 接口 | 权限（页面 / 按钮权限标识） |
| --- | --- |
| `/account/list`、`/account/role/options` | `manage_user` |
| `/account/create`、`update`、`status` | `manage_user:create`、`manage_user:update`、`manage_user:status` |
| `/account/role/update` | `manage_user:role` |
| `/role/list`、`menu/list` | `manage_role` |
| `/role/create`、`update`、`remove`、`menu/update` | `manage_role:create`、`:update`、`:remove`、`:menu` |
| `/menu/list` | `manage_menu` |
| `/menu/create`、`update`、`remove` | `manage_menu:create`、`:update`、`:remove` |
| `/system/setting/update` | `manage_setting:update` |
| `/system/backup/get` | `manage_setting` |
| `/system/backup/update`、`run`、`download`、`remove` | `manage_setting:backup` |
| `/system/operation-log/list` | `manage_operation_log` |
| `/data/source/list` | `data_source` 或 `data_template` |
| `/data/source/create`、`update`、`remove` | `data_source:create`、`:update`、`:remove` |
| `/data/source/test` | `data_source:create` 或 `data_source:update` |
| `/data/source/queries` | `data_template` |
| `/data/template/list` | `data_template` 或 `data_query` |
| `/data/template/create`、`update`、`remove` | `data_template:create`、`:update`、`:remove` |
| `/data/query` | `data_query` |
| `/data/query/export` | `data_query:export` |
| `/login`、`/logout`、`/access/menu`、`/profile/*` | 登录即可 |
| `/system/setting/get` | 不需要登录 |

预置按钮见 `schema.sql`，均为内置（不能修改、删除），因为服务器按它们的权限标识校验写接口。
