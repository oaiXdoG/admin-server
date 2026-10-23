# 个人设置

登录账号查看和修改自己的信息，只需登录，不需要菜单权限。

## 查看

`POST /profile/get`，请求体 `{}`。

返回：`id`、`account`、`username`、`avatar`（图片 data URL，未设置为空字符串）、`remark`、`lastLoginAt`、`createdAt`、`superAdmin`（是否超级管理员）、`roles`（角色名称列表）。

## 修改资料

`POST /profile/update`

```json
{"username":"爱丽丝","avatar":"data:image/webp;base64,....","remark":"运营部"}
```

`username` 必填最长 64；`remark` 最长 128；`avatar` 为空字符串表示不设置头像，否则必须是 png / jpeg / webp / gif 的 data URL，最长 280000 个字符（约 200KB）。成功返回修改后的个人信息。

## 修改密码

`POST /profile/password`，请求体 `{"oldPassword":"...","newPassword":"..."}`。

原密码必须正确；新密码 8～128 位且同时包含字母和数字。成功后该账号**其他**会话失效，当前会话保留。

错误：`100001` 参数不合法，`100003` 未登录，`100038` 原密码不正确，`100039` 图片格式不支持或超过大小限制，`100050` 密码强度不足。
