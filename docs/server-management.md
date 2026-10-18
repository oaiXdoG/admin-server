# 服务器管理（第一期）

每台业务服务器运行一个 Agent（`agent/` 目录，Java 17+ 单个 jar，说明见 `agent/README.md`），由它在本机托管业务进程（systemd）、执行指令和上报监控。后台不保存任何 SSH 账号、密码或密钥。

```
后台 admin-background ◀── Agent 每 5 秒调用 /api/agent/heartbeat：上报指标
                       ◀── Agent 长轮询 /api/agent/wait：后台有指令或文件操作时立即返回，最长挂起 25 秒
                       ◀── Agent 执行完调用 /api/agent/result：回传结果和输出，并立即补发一次心跳
业务服务器：Agent（systemd 服务 admin-agent）
   └─ 每个业务进程一个 systemd 服务 adm-<进程名>.service，ExecStart 就是 java ... -jar ...
```

已完成：服务器接入、进程登记（托管模式，含 JVM 参数模板、进程类型）、启动 / 停止 / 重启、部署（上传压缩包增量覆盖并重启）、文件管理（浏览、编辑、删除工作目录下的文件）、监控、执行记录。批量滚动、热更新在后续阶段。

## 一、接入流程

1. 后台「服务器管理 → 服务器 → 新增」：填名称、分组，保存后得到一条安装命令（令牌 10 分钟内有效，只能用一次）。
2. 运维登录这台服务器执行安装命令（需要 root 和 Java 17+）：

   ```bash
   curl -fsSL http://111.230.143.165/agent/install.sh | bash -s -- http://111.230.143.165 <令牌>
   ```

   脚本把 Agent 装到 `/opt/admin-agent`，注册成 systemd 服务 `admin-agent`（开机自启）。
3. Agent 启动后用令牌调用 `/api/agent/register`，换取服务器 ID 和专属密钥，写回本机 `/opt/admin-agent/agent.properties`（只有 root 可读），之后只用密钥签名通信。
4. 对已接入的服务器再次生成安装命令并执行，会重新注册并更换密钥（旧密钥立即失效），用于重装或密钥泄露时重置。

安装命令里的地址来自 `conf/application.properties` 的 `agent.base-url`，Agent 和安装脚本由 nginx 在 `/agent/` 下提供（见 admin-front `deploy/nginx.conf`）。

## 二、进程（托管模式）

| 字段 | 说明 |
| --- | --- |
| name | 进程名，同一服务器内唯一，创建后不能改；小写字母开头，小写字母、数字和 `-`，最长 48；systemd 服务名为 `adm-<name>` |
| workDir | 工作目录，绝对路径 |
| javaPath | java 可执行文件，默认 `java`（按 PATH 查找） |
| templateId | JVM 参数模板，可空 |
| jvmOptions | 本进程的 JVM 参数，追加在模板参数之后（同名参数后者生效） |
| jar | 启动 jar（`-jar` 方式），相对工作目录或绝对路径 |
| classpath / mainClass | `-cp` 方式：填了主类时用 `-cp <classpath> <主类>` 启动、不使用 jar，例如 `conf:lib/*:loginServer.jar` 和 `com.oaixdog.loginserver.Application`；`lib/*` 原样交给 java 展开 |
| programArgs | 程序参数，可空 |
| typeId | 进程类型，必填 |
| env | 环境变量，每行 `KEY=VALUE`，可空；不允许 `ADM_` 开头（后台注入用，见下文「服务绑定」），否则返回 `100001` |
| runUser | 运行用户，空为 root（建议用普通用户） |
| port | 启动后检查的端口，0 表示不检查 |

生成的 systemd 服务：

```ini
[Unit]
Description=<服务器名> <进程名>（admin-agent 托管）
After=network.target

[Service]
Type=simple
User=<runUser>
WorkingDirectory=<workDir>
EnvironmentFile=-/etc/admin-agent/env/adm-<name>.env
Environment="KEY=VALUE"
ExecStart=<javaPath> <模板参数> <jvmOptions> -jar <jar> <programArgs>
# 或 -cp 方式：ExecStart=<javaPath> <模板参数> <jvmOptions> -cp <classpath> <mainClass> <programArgs>
Restart=on-failure
RestartSec=5
TimeoutStopSec=60
SuccessExitStatus=143

[Install]
WantedBy=multi-user.target
```

- 进程定义修改后 `configVersion` 加 1，Agent 在下一次心跳时更新服务文件（`systemctl daemon-reload`），**重启后生效**；运行中且启动时的版本低于当前版本的进程显示「待重启」。
- 启动 / 重启：`systemctl enable` + `start/restart`，然后最多等 180 秒确认服务处于运行状态（配置了端口时同时确认端口可连），失败时附上 `systemctl status` 和最近 30 行 journal 日志。
- 停止：`systemctl stop`，先发 SIGTERM，60 秒未退出再 SIGKILL。
- 删除进程：只能删除已停止的进程；后台下发 `remove` 指令，Agent 禁用并删除服务文件和环境变量文件，不删除工作目录。

### 服务绑定（注入的环境变量）

业务服（登录服、回调服、游戏服）定时调用 `POST /api/node/report` 上报自己的信息（字段和验签见 `docs/服务发现改造.md`）。后台托管的进程启动时由 Agent 注入以下环境变量，业务服读到就用它们上报，配置文件里不需要写后台相关的东西：

| 变量 | 值 | 来源 |
| --- | --- | --- |
| `ADM_INSTANCE_ID` | 后台进程 ID，例如 `12`；业务服作为上报的 `instanceId` | 后台随进程定义下发 |
| `ADM_REPORT_SECRET` | 项目上报密钥明文 | 后台随进程定义下发 |
| `ADM_REPORT_URL` | `<Agent 的 baseUrl>/api/node/report` | Agent 自己拼 |

- 变量写在每个进程自己的文件 `/etc/admin-agent/env/adm-<name>.env`（权限 0600，Agent 0.4.0 起），服务文件用 `EnvironmentFile=-` 引用（带 `-`，文件不存在也能启动，只是不上报）。密钥不出现在服务文件和 `systemctl show` 里。
- 文件内容变化时与服务文件一样 `daemon-reload`；已运行的进程**重启后**才带上新变量（重置上报密钥后同样要重启）。
- 进程表单里的环境变量不允许 `ADM_` 开头。
- 不是后台启动的进程读不到这些变量，可以手动配置上报地址和密钥上报（「上报配置」），显示在「未托管服务」里。

### 服务状态

进程列表每项带 `typeCode`（类型编码，没有类型或类型没有编码时为空字符串）和 `service`（类型没有编码时为 null）：

```json
{
  "status": "online",          // online / not_ready / mismatch / offline
  "type": "logic", "nodeId": "logic-1", "instanceId": "12", "serverId": 1,
  "host": "111.230.143.165", "port": 9020, "innerHost": "10.0.0.8", "gmPort": 9021,
  "reportTime": 1760000000000,  // 毫秒，没有上报为 0
  "raw": { }                    // 原始上报对象，没有上报为 null
}
```

- 进程运行中（`state` 为 `active`）且 30 秒内有该进程 ID（`instanceId`）的上报 → `online`；上报的 `type` 与类型编码不同 → `mismatch`；
- 运行中但没有 30 秒内的上报 → `not_ready`；
- 进程未运行（含服务器离线）→ `offline`；
- 只要还有该实例最后一次上报，就带上 `type`、`reportTime` 等字段；没有上报时字符串字段为空、端口为 0、`serverId` 为 null。

## 三、监控

Agent 每 5 秒随心跳上报：

- 服务器信息：主机名、系统、CPU 核数、内存总量、内网 IP（本机网卡的 IPv4，跳过回环和 Docker、bridge、veth 等虚拟网卡，多个用逗号分隔）；公网 IP 为后台看到的访问来源（经 nginx 时取 X-Real-IP），多台机器共用出口时相同。
- 主机：CPU 使用率、1 分钟负载、内存已用 / 总量、根分区已用 / 总量、网络收发速率、每秒收发包数、已建立的 TCP 连接数、开机时长。
- 进程：systemd 状态、PID、CPU 使用率（占整机）、常驻内存、线程数、已建立的 TCP 连接数、启动时间、启动时的配置版本。

存储：

- Redis：心跳时间和最新指标，保留 60 秒，供列表页使用；超过 30 秒没有心跳显示离线。
- ClickHouse：每次心跳写入平台日志存储库（「系统设置 → 日志保留期」配置的库和管理账号）的 `server_metric` 表，主机一行（`process_id = 0`）、每个托管进程一行，保留 7 天。建表语句 `deploy/clickhouse/server_metric.sql`。使用 `async_insert` 由 ClickHouse 攒批写入，写入失败不影响心跳（告警每分钟最多打印一次）。

页面「监控详情」（隐藏菜单 `server_monitor`，从服务器列表 / 进程管理的「监控」进入）：选择服务器和对象（主机或某个进程），时间范围为实时（最近 1 小时，每 10 秒刷新）、近 24 小时、近 7 天或选择日期（7 天内），粒度 10 秒 / 1 分钟 / 5 分钟 / 1 小时（每条曲线最多约 2000 个点）。每个指标一行：曲线、Max / Min / Avg（当前曲线所有点）、放大查看；鼠标所在的曲线显示提示框；可导出 CSV。

## 四、执行记录

表 `server_task`。操作人每发起一次启动 / 停止 / 重启 / 部署 / 删除，按进程生成一条记录（action 为 `start` / `stop` / `restart` / `deploy` / `remove`；部署记录的 `remark` 为「文件名（大小）· 备注」）：等待 → 执行中（Agent 领取）→ 成功 / 失败，保存执行输出。等待超过 10 分钟或执行超过 5 分钟没有结果显示为「超时」。同一进程有未完成的指令时不能再发起新指令。服务器离线时不能发起指令。

## 五、进程类型、部署与文件管理

### 进程类型

项目内自定义的分类（例如 登录服、游戏服、回调服），表 `server_process_type`。进程必须选一个类型（`server_process.type_id`；库里旧数据可能为空），列表按类型筛选。有进程在用的类型不能删除。

业务类型编码 `code`：与业务服上报的 `type` 对应（登录服 `login`、回调服 `callback`、游戏服 `logic`），只允许小写字母、数字、下划线，最长 32，项目内非空时唯一；为空表示该类型的进程不参与服务绑定（不显示服务状态）。

### 部署（始终增量覆盖）

1. 页面选择 zip 压缩包，按 6MB 分块上传（base64 后约 8MB，在 nginx 10MB 的请求限制以内）（`/api/server/process/deploy/upload`），上传完成后调用 `/api/server/process/deploy` 生成「部署」指令。后台把压缩包存在 `server.package-dir`（默认 `packages`）下，指令结束后删除。
2. Agent 心跳领取指令后按块下载（`/api/agent/package`），校验 SHA-256，然后解压到进程的工作目录：
   - 压缩包根目录对应工作目录；整个包外面只有一层文件夹时自动去掉这一层；
   - 同路径的文件替换，新文件加入，包里没有的文件保持不变；
   - 拒绝包含 `..` 或绝对路径的条目；进程配置了运行用户时，解压出的文件归该用户所有。
3. 解压完成后重启进程（与「重启」指令相同：等待运行状态和端口），结果写入执行记录。部署失败不会回滚，需要时用旧的包再部署一次。

压缩包里不需要启动脚本，启动命令由进程定义生成。删除文件用文件管理。

### 文件管理

在进程详情里浏览、编辑、删除工作目录下的文件。操作范围限制在工作目录以内（路径为相对工作目录的路径，不能包含 `..`）。请求通过长轮询立即交给 Agent 执行（通常 1 秒以内），页面每 0.5 秒查询一次结果：

- `list`：列出目录（最多 1000 项），目录在前；
- `read`：读取文本文件，最大 1MB，含 `\0` 的按二进制文件拒绝；
- `write`：保存文本文件（只能修改已存在的文件，保留原有属主）；
- `delete`：删除文件或目录（目录递归删除），不能删除工作目录本身。

修改或删除后不会自动重启进程。

## 六、接口

### 后台页面接口

权限：查看需要项目菜单 `server_host`（服务器）或 `server_process`（进程）；操作按钮见下。

| 接口 | 权限 | 请求 → 返回 |
| --- | --- | --- |
| `POST /api/server/host/list` | `server_host` 或 `server_process` | `{ projectId }` → `{ list }`，每项含基本信息、`status`（`pending` 未接入 / `online` / `offline`）、`lastSeenAt`、`metric`（最新主机指标，离线为 null） |
| `POST /api/server/host/create` | `server_host:create` | `{ projectId, name, groupName, remark }` → `{ server, installCommand }` |
| `POST /api/server/host/update` | `server_host:update` | `{ projectId, serverId, name, groupName, remark }` → 服务器 |
| `POST /api/server/host/remove` | `server_host:remove` | `{ projectId, serverId }`；服务器下还有进程时不能删除 |
| `POST /api/server/host/install` | `server_host:update` | `{ projectId, serverId }` → `{ installCommand }`，生成新的一次性令牌 |
| `POST /api/server/process/list` | `server_process` | `{ projectId }` → `{ list }`，每项含定义（含 `typeId`、`typeName`、`typeCode`）、最近一次成功部署 `deployedAt`、`deployRemark`、`state`（`active` / `inactive` / `failed` / `activating` / `deactivating` / `unknown`，服务器离线时为 `unknown`）、`pendingRestart`、`metric`、`task`（未完成的指令）、`service`（服务状态，见上文） |
| `POST /api/server/process/create` | `server_process:create` | 进程字段（含必填的 `typeId`）+ `{ projectId, serverId }` → 进程 |
| `POST /api/server/process/update` | `server_process:update` | 进程字段（不含 name、serverId）+ `{ projectId, processId }` → 进程 |
| `POST /api/server/process/remove` | `server_process:remove` | `{ projectId, processId }` |
| `POST /api/server/process/operate` | `server_process:operate` | `{ projectId, processIds, action }`，action 为 `start` / `stop` / `restart`，一次最多 50 个 → `{ taskIds }` |
| `POST /api/server/process-type/list` | `server_process` | `{ projectId }` → `{ list }`，每项 `{ id, name, code, processCount }` |
| `POST /api/server/process-type/save` | `server_process:update` | `{ projectId, typeId, name, code }`，typeId 为空表示新建，名称 1～32 字，code 可空（规则见上文）→ 类型 |
| `POST /api/server/node/unmanaged` | `server_process` | `{ projectId }` → `{ list }`：未托管服务，即本项目上报里 `instanceId` 对应不到本项目任何进程的记录（含离线，超过 1 天没有上报的会被清掉），按 `reportTime` 倒序。每项 `{ type, nodeId, instanceId, serverId, host, port, innerHost, gmPort, reportTime, online, serverName, raw }`：`online` 为 30 秒内有上报，`serverName` 为 `innerHost` 匹配到的本项目服务器（服务器内网 IP 可能有多个，逗号分隔，任一相同即匹配；匹配不到为空字符串） |
| `POST /api/server/report-config` | `server_process:update` | `{ projectId }` → `{ appKey, reportUrl, secret }`：手动配置上报时用。`reportUrl` 为 `agent.base-url` + `/api/node/report`（未配置 `agent.base-url` 时为空字符串）；`secret` 为项目上报密钥明文，还没有时自动生成 |
| `POST /api/server/report-config/reset` | `server_process:update` | `{ projectId }` → 同上：重新生成上报密钥，旧密钥立即失效；托管进程的环境变量文件在下次心跳时更新，重启后生效，手动配置的服务要改配置。记操作日志（只记 projectId） |
| `POST /api/server/process-type/remove` | `server_process:update` | `{ projectId, typeId }`；有进程在用时不能删除 |
| `POST /api/server/process/deploy/upload` | `server_process:deploy` | `{ projectId, processId, uploadId, index, total, data }`：第一块 uploadId 为空，之后带上返回的 uploadId；index 从 0 开始按顺序上传，data 为该块的 base64（解码后不超过 6MB），total 不超过 64 → `{ uploadId }`。上传 1 小时内有效 |
| `POST /api/server/process/deploy` | `server_process:deploy` | `{ projectId, processId, uploadId, fileName, remark }`，所有块上传完成后调用；校验是 zip 且不为空，服务器在线、进程没有未完成的指令 → `{ taskId }` |
| `POST /api/server/process/file/request` | `server_process:file` | `{ projectId, processId, op, path, content }`，op 为 `list` / `read` / `write` / `delete`，path 相对工作目录（空为工作目录本身），content 只在 write 时使用（最大 1MB）→ `{ opId }` |
| `POST /api/server/process/file/result` | `server_process:file` | `{ projectId, opId }` → `{ status, success, message, data }`：status 为 `pending` / `done` / `timeout`（60 秒没有结果）；list 的 data 为 `{ entries: [{ name, dir, size, modifiedAt }] }`，read 的 data 为 `{ content, size }` |
| `POST /api/server/metric/query` | 项目 `server_monitor` | `{ projectId, serverId, processId, startTime, endTime, step }`（processId 为空查主机，step 为 10 / 60 / 300 / 3600 秒，范围不超过 7 天、每条曲线不超过 2100 个点）→ `{ list }`，每项 `{ time, cpu, load1, memoryUsed, memoryTotal, diskUsed, diskTotal, netIn, netOut, packetsIn, packetsOut, tcp, threads }`，为该时间段的平均值 |
| `POST /api/server/task/list` | `server_process` | `{ projectId, processId, page, pageSize }`（processId 可空）→ `{ total, list }`，按时间倒序 |
| `POST /api/server/template/list` | `server_process` | `{ projectId }` → `{ list }` |
| `POST /api/server/template/save` | `server_process:update` | `{ projectId, templateId, name, options }`，templateId 为空表示新建 → 模板 |
| `POST /api/server/template/remove` | `server_process:update` | `{ projectId, templateId }`；有进程在用时不能删除 |

错误码：`100001` 请求参数不合法（含进程没选类型、环境变量 `ADM_` 开头、类型编码格式不对）、`100080` 进程类型不存在、`100081` 进程类型名称已存在、`100082` 进程类型正在使用、`100089` 进程类型编码已存在、`100083` 发布包上传无效或已过期、`100084` 发布包不是有效的 zip 或为空、`100085` 文件操作不存在或已过期、`100065` 服务器不存在、`100066` 服务器名称已存在、`100067` 服务器下还有进程、`100068` 服务器离线、`100069` 进程不存在、`100070` 进程名已存在、`100071` 进程配置不合法、`100072` 进程有未完成的指令、`100073` 进程未停止、`100074` JVM 模板不存在、`100075` JVM 模板名称已存在、`100076` JVM 模板正在使用、`100077` 安装令牌无效、`100078` Agent 签名无效、`100079` 监控数据不可用（未配置 ClickHouse 管理账号）。

### Agent 接口

不需要登录。除注册外都要签名：请求头 `X-Agent-Server`（服务器 ID）、`X-Agent-Timestamp`（毫秒，与服务器时间相差不超过 5 分钟）、`X-Agent-Signature`（`HMAC-SHA256(密钥, 时间戳 + "\n" + SHA-256(请求体))` 的十六进制，SHA-256 也用十六进制小写）。

| 接口 | 请求 → 返回 |
| --- | --- |
| `POST /api/agent/register` | `{ token, hostname, os, privateIp, cpuCores, memoryTotal, agentVersion }` → `{ serverId, secret }` |
| `POST /api/agent/heartbeat` | `{ hostname, os, privateIp, cpuCores, memoryTotal, agentVersion, host, processes }` → `{ processes, tasks }`：`processes` 为本服务器全部进程定义（含合并后的 JVM 参数和 `configVersion`），`tasks` 为本次领取的指令 `{ taskId, processId, processName, action, param }`（部署指令的 param 为 `{ size, sha256, chunks }`），`fileOps` 为本次领取的文件操作 `{ opId, processId, op, path, content }`；进程定义含 `workDir`、`runUser`、`unit`（服务文件内容）、`env`（`{ "ADM_INSTANCE_ID": "<进程 ID>", "ADM_REPORT_SECRET": "<项目上报密钥>" }`，Agent 0.4.0 起写入环境变量文件并补上 `ADM_REPORT_URL`） |
| `POST /api/agent/wait` | `{ agentVersion }` → `{ tasks, fileOps }`（格式同心跳）：有待执行的指令或文件操作时立即返回，否则挂起，页面发起指令时立即返回，25 秒仍没有则返回空列表；Agent 收到后立即发起下一次。0.3.0 起心跳请求带 `longPoll: true`，后台不再随心跳下发指令 |
| `POST /api/agent/package` | `{ taskId, index }` → `{ data }`：部署指令的发布包第 index 块（6MB，base64），只能下载本服务器执行中的部署指令 |
| `POST /api/agent/file-result` | `{ opId, success, message, data }` → `null` |
| `POST /api/agent/result` | `{ taskId, success, output }` → `null` |

`host`：`{ cpu, load1, memoryUsed, memoryTotal, diskUsed, diskTotal, netIn, netOut, packetsIn, packetsOut, tcp, uptime }`（百分比 0～100，字节，字节/秒，个/秒，个，秒）。`processes[]`：`{ processId, state, pid, cpu, memory, threads, tcp, startedAt, configVersion }`。

## 七、菜单

服务器管理（`server`）下：服务器列表 `server_host`（按钮 `:create`、`:update`、`:remove`）、进程管理 `server_process`（按钮 `:create`、`:update`、`:remove`、`:operate`、`:deploy` 部署、`:file` 文件管理）、监控详情 `server_monitor`（隐藏菜单，不在侧边栏显示）。原来的性能监控、运维操作两个演示页面删除。
