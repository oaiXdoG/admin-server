# admin-report-client

业务服（游戏服、登录服、回调服等）向管理后台上报自身信息的依赖包：业务类型、节点 ID、实例 ID、对外端口等。管理后台据此把进程和托管服务对应起来，并用于按区自动发现等功能。

- 只依赖 JDK 17（`java.net.http.HttpClient`、`javax.crypto.Mac`、`MessageDigest`），不引入任何第三方库。
- 日志使用 `System.Logger`，会桥接到业务服使用的日志框架（如 SLF4J 提供了 `System.Logger` 桥接时）或 JUL。
- 上报在独立守护线程中进行，任何异常都不会抛到业务线程，也不会阻止进程退出。

## 坐标

```xml
<dependency>
    <groupId>com.oaixdog</groupId>
    <artifactId>admin-report-client</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

本机安装（与 log-sdk 相同，安装到本地仓库后业务服引用）：

```bash
cd admin-background/report-client
mvn install
```

## API

```java
AdminReporter.instanceId();   // 实例 ID：环境变量 ADM_INSTANCE_ID，没有则类加载时生成一次 UUID，进程存活期间不变
AdminReporter.start(appKey, type, nodeId, url, secret, content);   // 就绪后调用
AdminReporter.stop();         // 停止上报（可选，守护线程不阻止进程退出）
```

| 参数 | 说明 |
| --- | --- |
| `appKey` | 项目 appKey，作为 `X-Admin-Project` 请求头；为空则不上报 |
| `type` | 业务类型编码，如 `logic`、`login` |
| `nodeId` | 节点 ID，为空则请求体中不写 |
| `url` | 手动配置的上报地址；环境变量 `ADM_REPORT_URL` 非空时优先使用环境变量 |
| `secret` | 手动配置的上报密钥；环境变量 `ADM_REPORT_SECRET` 非空时优先使用环境变量 |
| `content` | 上报内容提供者，每次上报都会重新调用 `get()`，端口等变化能自动跟上 |

行为：

- 最终地址或密钥为空（或 appKey 为空）时，打一条 INFO「未配置管理后台上报地址或密钥，不上报」并直接返回。
- 启动后立即上报一次，之后每 10 秒一次。重复调用 `start` 会先停掉旧的再启动。
- 请求体为 `content` 的各项加上固定字段 `type`、`nodeId`、`instanceId`，同名键以固定字段为准。值只支持 String / Number / Boolean / null（其他类型按 `toString()` 作为字符串）。
- HTTP 200 且响应 `code` 为 `0` 视为成功，首次成功打一条 INFO；失败打 WARNING，同一类原因（状态码、错误码、异常类名）每分钟最多打一次。

## 接入示例

在服务完全就绪（端口已监听）后调用一次。

游戏服：

```java
AdminReporter.start(properties.getAppKey(), "logic", properties.getNodeId(), properties.getAdminReportUrl(), properties.getAdminReportSecret(),
        () -> Map.of("serverId", properties.getServerId(), "host", properties.getPublicHost(), "port", properties.getPort(),
                "innerHost", innerHost, "gmPort", properties.getGmPort()));
```

登录服 / 回调服（没有 GM 端口，`gmPort` 传 0）：

```java
AdminReporter.start(properties.getAppKey(), "login", properties.getNodeId(), properties.getAdminReportUrl(), properties.getAdminReportSecret(),
        () -> Map.of("host", properties.getPublicHost(), "port", properties.getPort(), "innerHost", innerHost, "gmPort", 0));
```

`Map.of` 不允许 null 值，内容可能为 null 时请改用 `HashMap`。

## 环境变量

由管理后台 Agent 启动托管进程时注入，业务服无需配置：

| 变量 | 说明 |
| --- | --- |
| `ADM_INSTANCE_ID` | 实例 ID，后台用它把上报和托管服务对应起来 |
| `ADM_REPORT_URL` | 上报地址 |
| `ADM_REPORT_SECRET` | 项目上报密钥 |

不是由后台启动的进程（例如本地开发或手动部署）没有这些变量，可以在业务服配置中手动填写上报地址和密钥，通过 `start` 的 `url`、`secret` 参数传入；此时实例 ID 为进程启动时生成的 UUID，后台显示为「未托管」。

## 请求与签名

```
POST {url}
Content-Type: application/json
X-Admin-Project: {appKey}
X-Admin-Timestamp: {当前毫秒时间戳}
X-Admin-Signature: {签名}
```

签名算法（十六进制均为小写）：

```
bodyHash  = hex(SHA-256(请求体 UTF-8 字节))
signature = hex(HMAC-SHA256(secret, timestamp + "\n" + bodyHash))
```

连接超时和请求超时均为 5 秒。
