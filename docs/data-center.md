# 数据中心

查询业务服务器自身的数据（玩家资料、订单状态等「当前状态」）。与日志中心（log-sdk 写入 ClickHouse 的事件日志，见 `log-center.md`）是两套来源。

admin 不直连业务数据库，而是调用业务服务器按下面规范提供的查询接口：业务方决定能查什么、对敏感字段先做处理；业务库是 PostgreSQL 还是 MongoDB 对 admin 没有区别。

## 一、页面与权限

| 页面 | 菜单 | 用途 |
| --- | --- | --- |
| 数据查询 | `data_query` | 选模板，按模板定义的参数填写条件，结果按模板的结果字段成列；点「详情」看完整返回（点表格其他位置不会打开，方便选中复制）；导出按钮 `data_query:export` |
| 查询模板 | `data_template` | 管理员选数据源和查询名，定义参数和结果字段；可从数据源的查询清单自动带出。按钮 `:create`、`:update`、`:remove` |
| 数据源 | `data_source` | 每个项目配置业务服务器的查询接口：固定地址，或按业务类型发现（同一业务类型的多个区 / 节点只配一次，地址来自服务上报）；签名密钥；可测试连接。按钮 `:create`、`:update`、`:remove` |

## 二、业务服务器接口规范

业务服务器提供两个 `POST` 接口，地址为数据源里配置的「接口地址」加路径（按业务类型发现时为各区 / 节点上报的 `innerHost:gmPort`，连接方式为公网时为 `host:gmPort`）：

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
| `X-Admin-Project` | 项目 App Key（一个业务服务器服务多个项目时用于区分） |
| `X-Admin-Operator` | 操作人登录名，业务方可自行记录审计日志 |

- 业务方必须校验签名，并拒绝与当前时间相差超过 5 分钟的请求。签名用请求体**原文**计算，不要先解析再序列化。

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

表 `data_source`：`name`（项目内唯一）、`type`、`service_type`、`network`、`url`、`secret`（用 `security.secret-key` AES-GCM 加密保存）、`timeout_ms`（1～60 秒）、`remark`。

`type` 两种：

- `http` 固定地址：`url` 为业务服务器接口地址，`service_type` 为空串。
- `discover` 按业务类型发现：`url` 为空，`service_type` 必填（业务类型编码，即进程类型的 `code`、上报的 `type`，只允许 `^[a-z0-9_]{1,32}$`）。各项目的业务服定时用 HTTP 上报到后台（`POST /api/node/report`，见下文「节点信息上报」），后台按 `App Key → instanceId` 保存在内存里。原来的 `zone`（按区自动发现）取消，升级时改为 `discover` + `service_type=logic`。

发现规则（`ServiceDiscoveryService.targets(appKey, serviceType, publicNetwork)`）：

- 取本项目上报中 `type = service_type`、在线（30 秒内有上报）、`gmPort > 0` 的记录；`gmPort` 为 0 的不提供查询接口、不参与查询。
- 接口地址按 `network`（连接方式）决定：`inner`（默认，后台和服务在同一个内网）为 `http://<上报的 innerHost>:<上报的 gmPort>`，`public`（不在同一个内网）为 `http://<上报的 host>:<上报的 gmPort>`，此时服务的 GM 端口要对后台服务器的 IP 开放。
- 可查询目标（target）：`service_type = logic`（游戏服）按区，`key` 为区号字符串（上报的 `serverId`，如 `"1"`），按区号数字排序；其他类型按节点，`key` 为上报的 `nodeId`（为空时用 `instanceId`），按 `key` 字符串排序。同一个 `key` 有多条记录时取最近上报的一条。
- 目标结构 `DataSourceTarget`：`{ key, zoneId, nodeId, url }`，`zoneId` 只有 logic 为区号，其他为 `null`；`nodeId` 为上报的 `nodeId`（未上报为空串）。
- 所有目标共用一个签名密钥和同一套查询模板，这个密钥只用于后台调用服务的 GM 接口（服务的 `gm.secret`），上报验签用项目上报密钥。后台重启后内存表为空，服务 10 秒内重新上报补齐。

| 接口 | 权限 | 说明 |
| --- | --- | --- |
| `POST /api/data/source/list` | `data_source` 或 `data_template` | `{ projectId }` → `{ list, serviceTypes }`。`list` 每项不返回密钥，只返回 `secretSet`、使用中的模板数 `templateCount`、`serviceType`、`serviceTypeName`（按 `serviceType` 匹配到的进程类型名称，匹配不到为空串）、`targetCount`（`discover` 类型当前可查询目标数，`http` 为 `null`）；`serviceTypes` 为本项目 `code` 非空的进程类型 `[{ code, name }]`，按 `code` 排序，供数据源表单选择业务类型（只有数据中心权限也能拿到） |
| `POST /api/data/source/create` | `data_source:create` | `{ projectId, name, type, serviceType, network, url, secret, timeoutMs, remark }`，type 只接受 `http`（默认）或 `discover`，network 为 `inner`（默认）或 `public`；`http` 时 url 必填，`discover` 时 serviceType 必填；密钥必填。返回同 list 的单项 |
| `POST /api/data/source/update` | `data_source:update` | 另加 `sourceId`；`secret` 留空沿用 |
| `POST /api/data/source/remove` | `data_source:remove` | `{ projectId, sourceId }`；被模板使用时返回 `100056` |
| `POST /api/data/source/test` | `data_source:create` 或 `:update` | `{ projectId, sourceId?, type, serviceType, network, url, secret, timeoutMs }`，调用 `/admin/queries`（`discover` 类型调用第一个可查询目标，没有可查询目标时返回 `100087`），返回 `{ queryCount, targets }`，`targets` 为发现的目标 `DataSourceTarget[]`（`http` 类型为空列表）；`sourceId` 有值且密钥留空时用已保存的密钥 |
| `POST /api/data/source/queries` | `data_template` | `{ projectId, sourceId }` → `{ queries }`，原样返回业务服务器的查询清单（`discover` 类型取第一个可查询目标） |
| `POST /api/data/source/targets` | `data_query` 或 `data_template` | `{ projectId, sourceId }` → `{ list }`，`discover` 类型数据源当前可查询的目标 `DataSourceTarget[]`，排序见发现规则；`http` 类型返回空列表 |

### 节点信息上报

`POST /api/node/report`：登录服、回调服、游戏服等业务服调用，不需要登录，按项目上报密钥签名鉴权（详见 `docs/服务发现改造.md`）。后台托管的进程由 Agent 注入上报地址 `ADM_REPORT_URL`、密钥 `ADM_REPORT_SECRET`、实例 ID `ADM_INSTANCE_ID`（见 `docs/server-management.md`「服务绑定」）；手动配置时地址和密钥在进程管理「上报配置」里查看。业务服就绪后立即上报一次，之后每 10 秒一次。

请求体：

```json
{ "type": "logic", "nodeId": "logic-1", "instanceId": "12", "serverId": 1, "host": "111.230.143.165", "port": 9020, "innerHost": "10.0.0.8", "gmPort": 9021 }
```

请求头：

| 请求头 | 值 |
| --- | --- |
| `X-Admin-Project` | 项目 App Key |
| `X-Admin-Timestamp` | 毫秒时间戳 |
| `X-Admin-Signature` | `hex(HMAC-SHA256(密钥, 时间戳 + "\n" + hex(SHA256(请求体))))`，小写十六进制 |

- 验签：按 `X-Admin-Project` 找项目，用项目上报密钥（`project.report_secret`）验签，不再读数据源密钥；App Key 不存在、项目还没有上报密钥、签名不对、时间戳与后台相差超过 5 分钟都返回 `100088`，不保存。拒绝时打印警告（App Key、instanceId、来源 IP、原因），同一 App Key + 来源 IP 每分钟最多一次。
- 参数：`type` 非空；`type=logic` 时 `serverId > 0`（其他类型可空）；`instanceId`、`host`、`innerHost` 非空；`port`、`gmPort` 在 0～65535；不合法返回 `100001`。请求体里不认识的字段忽略（节点新增字段时后台不用先升级），原始请求体保存下来在页面显示。
- 成功返回 `data: null`。按 `instanceId` 覆盖记录（区 ID 冲突由登录服、回调服拦截），30 秒内有上报视为在线；超过 1 天没有上报的记录在该项目下次收到上报时清掉。
- 记录在后台进程内存里，后台按单进程部署；以后多实例部署时要改为放 Redis。

### 查询模板

表 `data_template`：`source_id`、`name`（项目内唯一）、`query`、`params`（参数定义 JSON，最多 30 个）、`fields`（结果字段映射 JSON，最多 100 个，格式同日志模板，`source` 为结果行里的字段路径）。

| 接口 | 权限 |
| --- | --- |
| `POST /api/data/template/list` | `data_template` 或 `data_query`；每条带 `sourceName`、`sourceType`、`sourceServiceType`（查询页据此显示区服或节点选择：`discover` 且 `logic` 选区服，其他 `discover` 选节点） |
| `POST /api/data/template/create`、`update`、`remove` | `data_template:create`、`:update`、`:remove` |

### 数据查询

| 接口 | 权限 | 说明 |
| --- | --- | --- |
| `POST /api/data/query` | `data_query` | `{ projectId, templateId, targets, params, page, pageSize }`，`params` 为参数值 JSON 文本；返回 `{ total, list, failures }`，`list` 原样透传 |
| `POST /api/data/query/export` | `data_query:export` | 同上不分页，最多 5 万行 |

`discover` 类型数据源的模板：`targets` 必填且非空（一个或多个目标 `key`，即区号字符串或节点标识；为空时返回 `100061`「请选择区服」/「请选择节点」），每一行加上 `_target`（目标 key）。多个目标时同时查询，每个目标都按同样的 `page`、`pageSize` 取一页，结果按请求里 `targets` 的顺序（去重）合并，`total` 为各目标之和；查询失败或已离线（不在当前发现的目标里）的目标不影响其他目标，记在 `failures`（`[{ target, message }]`，离线时 message 为「不在线」）。`http` 类型忽略 `targets`，`failures` 为空。

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
| `100087` | 没有可查询的节点（需要在线且 GM 端口不为 0） |
| `100088` | 节点信息上报验签失败 |

### 安全约定

- 密钥加密保存，接口和页面不返回明文；操作日志里名称含 `secret`、`password` 的字段记为 `******`。
- admin 调用业务服务器时不跟随重定向，拒绝解析到链路本地（含云厂商元数据 `169.254.169.254`）、组播和通配地址的目标。
- 新建、修改、删除数据源和模板，以及导出，都记入操作日志。
