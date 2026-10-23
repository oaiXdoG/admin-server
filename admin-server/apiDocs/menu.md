# 菜单管理

菜单分为目录（`menuType=1`）、菜单（`menuType=2`）和按钮（`menuType=3`）。菜单权限通过角色关联（`role_menu`）；超级管理员由代码直接拥有全部启用菜单，新建菜单不需要关联。`builtIn=true` 的内置菜单不能修改或删除。

所有接口均为 `POST`，需要 `Authorization: Bearer <token>`，并要求调用者拥有 `manage_menu` 菜单权限；新增、修改、删除分别还需要按钮权限 `manage_menu:create`、`:update`、`:remove`。

## 列表

`POST /menu/list`，请求体 `{}`。

返回全量菜单，包含停用、隐藏和内置菜单，客户端按 `parentId` 组树。响应数据为 `{ "list": [...] }`。

## 新增

`POST /menu/create`。菜单 ID 由服务器生成，请求不再携带 `menuId`：顶级菜单依次为 10000、20000……；子孙菜单在所属顶级目录的段内自增（10001、10002……），每段最多 9999 个，超出返回 `100037`。移动到其他顶级目录的菜单保留原 ID。校验父级目录和字段。类型为菜单（`menuType=2`）时 `routeName` 必填；非空 `routeName` 在全部菜单中唯一，重复返回 `100030`。校验通过后写入菜单，生成的 ID 已存在时返回 `100026`。

```json
{
  "parentId": 30000,
  "menuType": 2,
  "name": "数据查询",
  "icon": "",
  "routeName": "data_query",
  "routePath": "/data/query",
  "status": 1,
  "hidden": false,
  "sort": 1
}
```

ID 分段只是编号约定，实际父子关系仍由 `parentId` 决定，不根据 ID 数字推断。`menu.id` 不是自增列，ID 由服务器算出后写入；MyBatis-Plus 实体使用 `IdType.INPUT`。

## 修改

`POST /menu/update`。先校验菜单存在、父级合法、`routeName` 规则（同新增）和子项约束（目录下只能是目录和菜单，菜单下只能是按钮，不满足返回 `100015`）；内置菜单返回 `100017`。

## 删除

`POST /menu/remove`。目录有子项返回 `100011`，内置菜单返回 `100017`。

校验通过后，先删除该菜单的全部 `role_menu` 关联，再删除菜单记录；两个操作处于同一事务，失败时回滚，避免 `fk_role_menu_menu` 外键错误。不删除角色，不递归删除子菜单。

参考：[MyBatis-Plus 主键策略](https://baomidou.com/en/reference/annotation/)、[Spring 事务回滚](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/rolling-back.html)。

错误：`100001` 参数不合法，`100003` 未登录，`100009` 菜单不存在，`100010` 父级不合法，`100011` 目录非空，`100015` 目录不能改菜单，`100016` 无权访问，`100017` 内置菜单不可变更，`100026` 菜单 ID 已存在，`100030` 路由名称已被使用，`100037` 段内菜单数量已达上限。
