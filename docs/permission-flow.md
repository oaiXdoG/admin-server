# 权限执行顺序

完整设计见 `docs/permission-redesign.md`。

## 三类角色

| 类型 | `role_type` | 所属 | 分配方式 | 可分配菜单 |
| --- | --- | --- | --- | --- |
| 平台角色 | 1 | 无 | `account_role` | 平台菜单（`scope=1`） |
| 角色模板 | 2 | 无 | 不直接分配，复制为项目角色 | 项目菜单（`scope=2`） |
| 项目角色 | 3 | `project_id` | `project_user` | 项目菜单（`scope=2`） |

内置平台角色「超级管理员」（`SUPER_ADMIN`）可以切换所有未关闭项目，在任何项目中拥有全部启用的项目菜单，不需要成为成员。

## 流程

1. 登录校验账号状态和 bcrypt 密码，建立会话令牌。
2. 客户端调用 `/api/project/list` 确定会话当前项目，再携带 `currentProjectId` 调用 `/api/access/menu`。
3. 菜单 = 平台角色的平台菜单 + 当前项目中项目角色的项目菜单（超级管理员为全部项目菜单）。只返回启用菜单。
4. 平台接口调用 `PermissionService.requirePlatformMenu(authorization, routeName)`，只认平台角色。
5. 项目接口调用 `PermissionService.requireProjectMenu(authorization, projectId, routeName)`：请求项目必须等于会话当前项目，项目未关闭，账号是成员或超级管理员，并拥有目标菜单。
6. 项目角色无论怎么配置都拿不到平台菜单，因此不能通过项目角色获得系统管理权限。
7. 写接口校验页面下的按钮（菜单类型 3），按钮的 `route_name` 是权限标识，同样通过上面两个方法校验；查看类接口只校验页面。客户端从 `/api/access/menu` 返回的按钮得到权限标识，用来隐藏没有权限的按钮。

## 接口权限

| 接口 | 权限（页面 / 按钮权限标识） |
| --- | --- |
| `/api/project/manage/list` | 平台 `manage_project` |
| `/api/project/create`、`update` | `manage_project:create`、`manage_project:update` |
| `/api/project/close`、`reopen` | `manage_project:status` |
| `/api/account/list`、`project/list`、`project/options`、`project/roles`、`role/options` | 平台 `manage_user` |
| `/api/account/create`、`update`、`status` | `manage_user:create`、`manage_user:update`、`manage_user:status` |
| `/api/account/role/update`、`project/update` | `manage_user:role`、`manage_user:project` |
| `/api/role/list`、`menu/list` | 平台 `manage_role` |
| `/api/role/create`、`update`、`remove`、`menu/update` | `manage_role:create`、`:update`、`:remove`、`:menu` |
| `/api/role-template/list`、`menu/list` | 平台 `manage_role_template` |
| `/api/role-template/create`、`update`、`remove`、`menu/update` | `manage_role_template:create`、`:update`、`:remove`、`:menu` |
| `/api/menu/list` | 平台 `manage_menu` |
| `/api/menu/create`、`update`、`remove` | `manage_menu:create`、`:update`、`:remove` |
| `/api/system/setting/update` | `manage_setting:update` |
| `/api/system/log-storage/get` | 平台 `manage_setting` |
| `/api/system/log-storage/update`、`test`、`retention` | `manage_setting:storage` |
| `/api/project/member/list`、`roles` | 项目 `project_member` |
| `/api/project/member/add`、`update`、`remove` | `project_member:add`、`:update`、`:remove` |
| `/api/project/role/list`、`menu/list`、`templates` | 项目 `project_role` |
| `/api/project/role/create`、`update`、`remove`、`menu/update`、`import` | `project_role:create`、`:update`、`:remove`、`:menu`、`:import` |
| `/api/log/query` | 项目 `log_query` |
| `/api/log/export` | `log_query:export` |
| `/api/log/event/list`、`fields` | 项目 `log_query` 或 `log_event` |
| `/api/log/event/dict/list` | 项目 `log_query` 或 `log_event` |
| `/api/log/event/dict/save`、`remove` | `log_event:update`、`:remove` |
| `/api/log/source/get` | 项目 `log_source` |
| `/api/log/source/save`、`test` | `log_source:update` |
| `/api/server/host/list` | 项目 `server_host` 或 `server_process` |
| `/api/server/metric/query` | 项目 `server_monitor` |
| `/api/server/host/create`、`update`、`install`、`remove` | `server_host:create`、`:update`、`:update`、`:remove` |
| `/api/server/process/list`、`/api/server/task/list`、`/api/server/template/list`、`/api/server/node/unmanaged` | 项目 `server_process` |
| `/api/server/process/create`、`update`、`remove`、`operate` | `server_process:create`、`:update`、`:remove`、`:operate` |
| `/api/server/template/save`、`remove` | `server_process:update` |
| `/api/server/report-config`、`/api/server/report-config/reset` | `server_process:update` |
| `/api/agent/register`、`heartbeat`、`result` | 不需要登录：注册用一次性安装令牌，心跳和结果按服务器密钥验签 |
| `/api/node/report` | 不需要登录：业务服按项目上报密钥验签 |
| `/api/data/source/list` | 项目 `data_source` 或 `data_template` |
| `/api/data/source/create`、`update`、`remove` | `data_source:create`、`:update`、`:remove` |
| `/api/data/source/test` | `data_source:create` 或 `data_source:update` |
| `/api/data/source/queries` | 项目 `data_template` |
| `/api/data/template/list` | 项目 `data_template` 或 `data_query` |
| `/api/data/template/create`、`update`、`remove` | `data_template:create`、`:update`、`:remove` |
| `/api/data/query` | 项目 `data_query` |
| `/api/data/query/export` | `data_query:export` |
| `/api/login`、`/api/logout`、`/api/project/list`、`/api/project/switch`、`/api/access/menu`、`/api/profile/*` | 登录即可 |
| `/api/system/setting/get` | 不需要登录 |

预置按钮见 `schema.sql`，均为内置（不能修改、删除），因为服务器按它们的权限标识校验写接口。超级管理员在启动时获得全部平台菜单（含按钮），在项目里拥有全部项目菜单（含按钮）。
