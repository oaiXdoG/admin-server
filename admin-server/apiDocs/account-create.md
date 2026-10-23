# 创建账号

`POST /account/create`。需要按钮权限 `manage_user:create`。

```json
{"account":"alice","password":"123456","username":"爱丽丝","remark":"运营部"}
```

`account` 必填、去除首尾空白后最长 64 且唯一；`password` 8～128 位且同时包含字母和数字，按 bcrypt 哈希保存；`username` 必填最长 64；`remark` 可选最长 128。新账号为启用状态，不绑定角色，需要再通过「分配角色」授权。

成功 `data`：`{"id":2,"account":"alice","username":"爱丽丝"}`。

错误：`100001` 参数不合法，`100003` 未登录，`100006` 登录名已存在，`100016` 无权访问，`100050` 密码强度不足。
