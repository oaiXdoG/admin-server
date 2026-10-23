# 登录

`POST /login`

请求头：

```http
Content-Type: application/json
```

不需要登录令牌。

## 请求体

```json
{
  "account": "admin",
  "password": "password"
}
```

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `account` | String | 是 | 登录名，对应账号表 `account`。去掉首尾空白后不能为空，最长 64 |
| `password` | String | 是 | 密码，与账号表 `password_hash`（bcrypt）比对。不能为空，最长 128 |

## 成功

HTTP `200`。

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "token": "登录令牌",
    "id": 1,
    "account": "admin",
    "username": "管理员"
  }
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `token` | String | 后续请求携带的登录令牌 |
| `id` | long | 账号 `id` |
| `account` | String | 登录名 |
| `username` | String | 显示名 |

响应不返回 `password`。

## 失败

业务失败 HTTP 仍为 `200`，`data` 为 `null`。码的边界见 `apiDocs/error-code.md`。登录只返回下面两个业务判断，不返回服务器内部故障码。

| code | message | 判断 |
| --- | --- | --- |
| 100001 | 请求参数不合法 | `account` 或 `password` 为空、超长，或请求体无法按登录请求读取 |
| 100002 | 账号或密码错误 | 账号不存在，或密码与库中哈希不匹配。不区分是哪一种 |
| 100031 | 账号已禁用 | 账号状态为禁用 |

```json
{
  "code": 100002,
  "message": "账号或密码错误",
  "data": null
}
```

## 后续请求

登录成功后，请求头携带：

```http
Authorization: Bearer <token>
```

登录后客户端应调用 `/access/menu` 获取菜单权限。

令牌缺失或失效时：

```json
{
  "code": 100003,
  "message": "未登录",
  "data": null
}
```

## 会话

登录成功后，Redis 中写入 `login:token:<token>`（账号 ID）并把令牌加入 `login:account:<账号ID>` 集合，有效期 12 小时；同时记录 `last_login_at`。每次通过登录校验的请求把令牌续到 12 小时（滑动过期）。禁用账号、修改密码时该账号的全部会话失效。

# 退出登录

`POST /logout`，请求体 `{}`。删除当前会话；令牌已失效时同样返回成功，`data` 为 `null`。
