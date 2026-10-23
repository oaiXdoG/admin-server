# 账号列表

`POST /account/list`，请求体 `{}`。需要菜单 `manage_user`。

返回全部账号，按 ID 排序，不返回密码哈希。每项字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | number | 账号 ID |
| account | string | 登录名 |
| username | string | 显示名 |
| status | number | 1 启用，2 禁用 |
| remark | string | 备注 |
| lastLoginAt | number | 最后登录时间，从未登录时字段省略 |
| createdAt | number | 创建时间 |
| roles | array | 角色 `[{id, name, code}]` |

错误：`100003` 未登录，`100016` 无权访问。
