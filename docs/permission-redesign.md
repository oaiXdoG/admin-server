# 系统设置改造方案（单项目、单一角色）

状态：已实施。2026-10-13 实施过一版「平台 / 模板 / 项目」三层角色，正式库已迁移（迁移前备份库：`admin_background_bak_premigrate`）；2026-10-23 改为一套部署只服务一个项目，去掉项目、角色模板和项目角色，角色只剩一种。

## 一、已确定的决策

| # | 决策 |
| --- | --- |
| 1 | 一套部署只服务一个项目，后台没有多项目概念；表里不区分项目，业务接口不接收 `projectId` |
| 2 | 角色只有一种，通过 `account_role` 分配给账号（可多个），可分配全部菜单 |
| 3 | 内置角色「超级管理员」（`SUPER_ADMIN`）拥有全部启用菜单，由代码直接放行，不写 `role_menu` |
| 4 | 账号由「用户管理」创建，在同一页面「分配角色」 |
| 5 | 按钮级权限（菜单类型 3）已于 2026-10-14 实施，见 `permission-flow.md` |

同时纳入的改造：

- 密码改为 bcrypt 哈希存储
- 账号增加启用 / 禁用；禁用、改密码时让该账号已登录的会话失效
- 新增退出登录接口

## 二、通用约定

- 状态统一：`1` 启用，`2` 停用
- 时间统一：毫秒时间戳 `bigint`；所有对象有 `created_at`、`updated_at`
- 权限判断：账号的启用角色关联的启用菜单；超级管理员直接通过

## 三、三个对象

### 1. 菜单 `menu`

| 字段 | 类型 | 规则 |
| --- | --- | --- |
| id | bigint | 服务器按顶级目录分段生成（顶级为 10000 的整数倍，子孙在段内自增） |
| parent_id | bigint | 为空表示顶级；目录和菜单的上级必须是目录，按钮的上级必须是菜单 |
| menu_type | smallint | 1 目录，2 菜单，3 按钮 |
| name | varchar(64) | 必填 |
| icon | varchar(64) | iconify 名称 |
| route_name | varchar(64) | 菜单和按钮必填，全局唯一；按钮保存权限标识，例如 `manage_user:create` |
| route_path | varchar(128) | 选择已注册路由时带出 |
| status | smallint | 1 启用，2 停用 |
| hidden | boolean | 只影响侧栏显示 |
| built_in | boolean | 内置菜单不能修改、删除 |
| sort_order | int | 同级排序 |
| created_at / updated_at | bigint | |

预置菜单：

```
系统管理 10000   用户管理 manage_user / 角色管理 manage_role / 菜单管理 manage_menu
                 系统设置 manage_setting / 操作日志 manage_operation_log
数据中心 30000   数据查询 / 查询模板 / 数据源
```

界面（系统管理 → 菜单管理）：

- 搜索：名称/路由
- 表格（树）：名称、类型、路由名称 / 权限标识、路由路径、状态、隐藏、排序、操作
- 表单：类型、上级目录、已注册路由、名称、权限标识（按钮）、图标、路由名称、路由路径、状态、隐藏、排序

### 2. 角色 `role`

| 字段 | 类型 | 规则 |
| --- | --- | --- |
| id | bigint | |
| name | varchar(32) | 全表唯一 |
| code | varchar(64) | 全表唯一；只允许大写字母、数字、下划线，字母开头 |
| description | varchar(128) | |
| status | smallint | 停用后不再提供菜单权限 |
| built_in | boolean | 超级管理员为内置角色，不能修改、删除 |
| created_at / updated_at | bigint | |

可分配菜单：全部菜单，子项必须带上级。

初始角色（`schema.sql` 写入，普通角色，可改可删）：前端 `FRONTEND`、测试 `TESTER`、运营 `OPERATOR` 为数据查询（含导出），后端 `BACKEND` 为数据中心全部菜单。

界面（系统管理 → 角色管理）：搜索名称、状态；表格 ID、名称、编码、描述、状态（行内开关）、创建时间、操作（编辑、分配菜单、删除）；分配菜单弹窗显示全部菜单树。

### 3. 账号 `account`

| 字段 | 类型 | 规则 |
| --- | --- | --- |
| id | bigint | |
| account | varchar(64) | 登录名，不能修改 |
| password_hash | varchar(100) | bcrypt；任何接口都不返回 |
| username | varchar(64) | 显示名 |
| avatar | text | 头像 data URL |
| status | smallint | 1 启用，2 禁用；禁用账号不能登录，已登录会话立即失效；不能禁用自己 |
| remark | varchar(128) | 备注 |
| last_login_at | bigint | |
| created_at / updated_at | bigint | |

关联：`account_role(account_id, role_id)`。只有超级管理员能分配或撤销「超级管理员」，并且不能撤销自己的。

界面（系统管理 → 用户管理）：搜索登录名/显示名、状态；表格 ID、登录名、显示名、角色、备注、状态（行内开关）、最后登录、创建时间、操作（编辑、分配角色）。编辑：显示名、备注、重置密码。

## 四、接口清单

沿用 `POST` + JSON + `{code,message,data}`。按钮级权限标识见 `permission-flow.md`。

| 接口 | 权限 | 说明 |
| --- | --- | --- |
| `/login` | — | 校验账号状态，记录 last_login_at |
| `/logout` | 登录 | 删除当前会话 |
| `/access/menu` | 登录 | 请求体 `{}`；返回账号拥有的启用菜单（含按钮），超级管理员为全部启用菜单 |
| `/menu/*` | manage_menu | |
| `/role/list`、`create`、`update`、`remove`、`menu/list`、`menu/update` | manage_role | |
| `/account/list` | manage_user | 含 status、remark、roles、lastLoginAt、createdAt |
| `/account/create`、`update` | manage_user | 密码按哈希保存 |
| `/account/status` | manage_user | 启用 / 禁用 |
| `/account/role/options` | manage_user | 启用的角色 |
| `/account/role/update` | manage_user | 整体替换账号角色 |

## 五、会话与密码

- Redis：`login:token:<token>` → 账号 ID；`login:account:<id>` → 该账号的令牌集合，用于禁用、改密码、退出时批量失效。TTL 与令牌一致（12 小时）
- 密码使用 `spring-security-crypto` 的 `BCryptPasswordEncoder`
- （2026-10-14）会话改为滑动过期：每次通过 `requireAccount` 的请求把令牌和令牌集合续到 12 小时，12 小时无操作才失效
- （2026-10-14）密码规则 `PasswordPolicy`：8～128 位，同时包含字母和数字；新建账号、重置密码、本人改密码都校验，不符合返回 `100050`。已有账号的旧密码不受影响，下次修改时生效
- （2026-10-14）登录防爆破：`login:fail:account:<登录名小写>`、`login:fail:ip:<IP>` 记录失败次数，从第一次失败起 15 分钟；账号 5 次或 IP 20 次后返回 `100049`，锁定期间密码正确也不登录；登录成功清除该账号的计数。IP 取连接地址，只有连接来自本机（同机 nginx）时才信任 `X-Real-IP`

## 六、存量数据迁移

三层角色版本已于 2026-10-13 完成迁移。之后数据库按 `schema.sql` 重新初始化，不保留迁移脚本。初始化过程见 `docs/initialization.md`。
