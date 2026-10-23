# 当前账号菜单

`POST /access/menu`

客户端登录后调用，返回当前账号可用的菜单（含按钮）。

请求体：`{}`。

## 处理顺序

1. 校验令牌和账号状态（禁用账号视为未登录）。
2. 超级管理员（拥有启用的内置角色 `SUPER_ADMIN`）：全部启用菜单，不需要 `role_menu` 关联。
3. 其他账号：账号拥有的启用角色（`account_role`）关联的菜单（`role_menu`）。
4. 只返回 `status=1` 的菜单，按父级、`sort`、`id` 排序。`hidden=true` 的菜单仍返回，只是不在侧栏展示。

## 成功

每项字段：`id`、`parentId`（顶级为 0）、`menuType`、`name`、`icon`、`routeName`、`routePath`、`status`、`hidden`、`builtIn`、`sort`。

## 失败

`100003` 未登录。

服务器业务接口会按同一套规则再次校验，客户端隐藏菜单不能绕过权限。
