# 数据中心

查询业务服务器自身的数据（玩家资料、订单状态等「当前状态」）。日志改由 log 服务提供，与数据中心无关。

admin 不直连业务数据库，而是调用业务服务器按下面规范提供的查询接口：业务方决定能查什么、对敏感字段先做处理；业务库是 PostgreSQL 还是 MongoDB 对 admin 没有区别。

## 一、页面与权限

| 页面 | 菜单 | 用途 |
| --- | --- | --- |
| 数据查询 | `data_query` | 选模板，按模板定义的参数填写条件，结果按模板的结果字段成列；点「详情」看完整返回（点表格其他位置不会打开，方便选中复制）；导出按钮 `data_query:export` |
| 查询模板 | `data_template` | 管理员选数据源和查询名，定义参数和结果字段；可从数据源的查询清单自动带出。按钮 `:create`、`:update`、`:remove` |
| 数据源 | `data_source` | 配置业务服务器的查询接口：固定地址、签名密钥；可测试连接。按钮 `:create`、`:update`、`:remove` |

## 二、业务服务器接口规范

业务服务器提供两个 `POST` 接口，地址为数据源里配置的「接口地址」加路径：

| 路径 | 用途 |
| --- | --- |
| `/admin/queries` | 查询清单：可用的查询、参数和结果字段（admin 建模板时读取，测试连接也调用它） |
| `/admin/query` | 执行查询 |

### 请求

- `Content-Type: application/json`，请求体为 JSON。
- 请求头：

| 请求头 | 说明 |
| --- | --- |
| `X-Admin-Timestamp` | 毫秒时间戳 |
| `X-Admin-Signature` | `HMAC-SHA256(密钥, 时间戳 + "\n" + 请求体原文)`，小写十六进制 |
| `X-Admin-Operator` | 操作人登录名，业务方可自行记录审计日志 |

- 业务方必须校验签名，并拒绝与当前时间相差超过 5 分钟的请求。签名用请求体**原文**计算，不要先解析再序列化。
- 后台不发 `X-Admin-Project`（一套部署只服务一个项目）。

`/admin/query` 的请求体：

```json
{ "query": "order_list", "params": { "playerId": "1001", "status": "paid", "minAmount": 30, "since": 1760410116381 }, "page": 1, "pageSize": 20 }
```

- `params` 只包含模板里定义且有值的参数，类型已按定义转换：文本和下拉为字符串，数字为数字，是/否为布尔，日期时间为毫秒时间戳。
- `pageSize` 最大 200；导出时 admin 按 `pageSize=1000` 逐页取，最多 5 万行。

`/admin/queries` 的请求体为 `{}`。

### 响应

HTTP 200，统一外层：

```json
{ "code": 0, "message": "", "data": { } }
```

`code` 不为 0 时 admin 把 `message` 展示给使用者（例如「玩家不存在」「签名错误」）。

`/admin/query` 的 `data`：

```json
{ "total": 120, "list": [ { "orderId": "O0001", "playerId": "1001", "amount": 6, "status": "paid", "paidAt": 1760410116381, "profile": { "server": "S1" } } ] }
```

`list` 的每一行是任意 JSON 对象，admin 按模板的结果字段（字段名或用点连接的路径，如 `profile.server`）取值展示，点开详情可看到整行。

`/admin/queries` 的 `data`：

```json
{
  "queries": [
    {
      "name": "order_list",
      "label": "订单列表",
      "description": "按条件查询充值订单",
      "params": [
        { "name": "playerId", "label": "玩家 ID", "type": "string", "required": false },
        { "name": "status", "label": "状态", "type": "select", "required": false,
          "options": [ { "label": "已支付", "value": "paid" }, { "label": "已退款", "value": "refunded" } ] },
        { "name": "minAmount", "label": "最低金额", "type": "number" },
        { "name": "since", "label": "起始时间", "type": "time" }
      ],
      "fields": [
        { "key": "orderId", "label": "订单号", "type": "string" },
        { "key": "amount", "label": "金额", "type": "number" },
        { "key": "paidAt", "label": "支付时间", "type": "time" }
      ]
    }
  ]
}
```

- 参数 `type`：`string`、`number`、`boolean`、`time`、`select`。
- 字段 `type`：`string`、`number`、`boolean`、`time`（毫秒或日期文本）、`json`（对象/数组）、`jsonText`（JSON 字符串）。
- 查询名：字母开头，字母、数字和 `_.-`，最长 64；参数名：字母、数字、下划线。

### 签名校验示例（Java）

```java
String body = new String(rawBody, StandardCharsets.UTF_8);          // 请求体原文
long timestamp = Long.parseLong(request.getHeader("X-Admin-Timestamp"));
if (Math.abs(System.currentTimeMillis() - timestamp) > 300_000) {
    return error(401, "请求已过期");
}
Mac mac = Mac.getInstance("HmacSHA256");
mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
String expected = HexFormat.of().formatHex(mac.doFinal((timestamp + "\n" + body).getBytes(StandardCharsets.UTF_8)));
if (!MessageDigest.isEqual(expected.getBytes(), request.getHeader("X-Admin-Signature").getBytes())) {
    return error(401, "签名错误");
}
```

## 三、admin 接口

### 数据源

表 `data_source`：`name`（全表唯一）、`url`（业务服务器接口地址，固定地址）、`secret`（用 `security.secret-key` AES-GCM 加密保存）、`timeout_ms`（1～60 秒）、`remark`。

| 接口 | 权限 | 说明 |
| --- | --- | --- |
| `POST /data/source/list` | `data_source` 或 `data_template` | `{}` → `{ list }`。每项 `id`、`name`、`url`、`secretSet`（不返回密钥）、`timeoutMs`、`remark`、使用中的模板数 `templateCount`、`createdAt`、`updatedAt` |
| `POST /data/source/create` | `data_source:create` | `{ name, url, secret, timeoutMs, remark }`，url、密钥必填。返回同 list 的单项 |
| `POST /data/source/update` | `data_source:update` | 另加 `sourceId`；`secret` 留空沿用 |
| `POST /data/source/remove` | `data_source:remove` | `{ sourceId }`；被模板使用时返回 `100056` |
| `POST /data/source/test` | `data_source:create` 或 `:update` | `{ sourceId?, url, secret?, timeoutMs }`，调用 `/admin/queries`，返回 `{ queryCount }`；`sourceId` 有值且密钥留空时用已保存的密钥 |
| `POST /data/source/queries` | `data_template` | `{ sourceId }` → `{ queries }`，原样返回业务服务器的查询清单 |

### 查询模板

表 `data_template`：`source_id`、`name`（全表唯一）、`query`、`params`（参数定义 JSON，最多 30 个）、`fields`（结果字段映射 JSON，最多 100 个，格式同日志模板，`source` 为结果行里的字段路径）。

| 接口 | 权限 |
| --- | --- |
| `POST /data/template/list` | `data_template` 或 `data_query`；每条带 `sourceName` |
| `POST /data/template/create`、`update`、`remove` | `data_template:create`、`:update`、`:remove` |

### 数据查询

| 接口 | 权限 | 说明 |
| --- | --- | --- |
| `POST /data/query` | `data_query` | `{ templateId, params, page, pageSize }`，`params` 为参数值 JSON 文本；返回 `{ total, list }`，`list` 原样透传 |
| `POST /data/query/export` | `data_query:export` | 同上不分页，最多 5 万行 |

参数校验：必填为空返回 `100061`（「请填写「xxx」」）；数字、时间无法解析，是/否不是 true/false，下拉值不在选项里，文本超过 512 字，都返回 `100061`。

### 错误码

| 码 | 含义 |
| --- | --- |
| `100054` | 数据源不存在 |
| `100055` | 数据源名称已存在 |
| `100056` | 数据源正在被查询模板使用 |
| `100057` | 业务数据源请求失败，消息里附带原因（无法连接、HTTP 状态、业务返回的 code 和 message 等） |
| `100058` / `100059` / `100060` | 查询模板不存在 / 名称已存在 / 配置不合法 |
| `100061` | 查询参数不合法 |

### 安全约定

- 密钥加密保存，接口和页面不返回明文；操作日志里名称含 `secret`、`password` 的字段记为 `******`。
- admin 调用业务服务器时不跟随重定向，拒绝解析到链路本地（含云厂商元数据 `169.254.169.254`）、组播和通配地址的目标。
- 新建、修改、删除数据源和模板，以及导出，都记入操作日志。
