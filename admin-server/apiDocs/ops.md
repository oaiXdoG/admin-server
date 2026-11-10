# 运维中心

管理服务器和服务器上的程序。管理后台调用本机的 `ssh` 命令，以 root 登录服务器执行命令。

- **主机**：在后台手动添加（IP、SSH 端口），保存在 `ops_host`。添加后点「初始化」安装程序运行需要的 JDK 21、unzip。
- **程序**：保存在 `ops_app`，每条绑定一台主机和它上面的一个目录（绝对路径，自己配置，例如 `/usr/local/games/game1`）。同一台主机上名称、目录都不能重复。
  - 登记时把 `ops-scripts/app.sh` 拷到 `<目录>/bin/app.sh`，每次发布前再覆盖成最新版本。所有程序共用这一份脚本，它从自己所在位置推导程序目录，不需要参数或配置。
  - 启停、发布、回滚、日志、配置文件都是通过 SSH 执行 `bash <目录>/bin/app.sh <命令>`；在服务器上手动执行 `<目录>/bin/app.sh start|stop|restart|status` 效果一样。没有 systemd，也没有自动拉起。
- **后台执行**：初始化、启停、发布、回滚、保存配置都在后台执行，接口立即返回 `taskId`，发起操作的页面用 `/ops/task/get` 读实时输出。同一个程序同一时间只能有一个。谁做了什么、成功还是失败，在结束时写入操作日志，没有单独的历史页。
- **监控**：主机「初始化」时装上 node_exporter、process-exporter，由 Prometheus 拉取；「主机监控」页嵌入 Grafana 的面板（经 nginx 以同源的 `/grafana/` 访问）。管理后台只负责把主机列表写给 Prometheus（`ops.prometheus.targets-dir`）和提供下拉选项。

`app.sh`、`host_init.sh` 在 admin-server 的 `ops-scripts/` 目录，随程序一起打包。

## 程序目录的格式

`app.sh` 只认这个格式：

| 路径 | 说明 |
| --- | --- |
| `bin/app.sh` | 管理后台拷进来的管理脚本 |
| `lib/` | jar，其中恰好一个 jar 的 MANIFEST 带 `Main-Class`；`lib/extra/` 也在 classpath 上 |
| `conf/` | 配置文件，放在 classpath 最前面；`conf/jvm.options` 为 JVM 参数（每行一个，`#` 开头和空行忽略，可以没有） |
| `excel/` | 可选 |
| `logs/` | 程序自己写的日志；`console.log` 为最近一次启动的控制台输出（JVM 起不来时看这里） |
| `app.pid` | 运行中的进程号 |
| `backup/<时间>/` | 发布、回滚前的 `lib`、`conf`、`excel` |
| `backup-conf/<时间>/` | 在线修改配置前的旧文件 |

启动命令：`cd <目录> && java <jvm.options> -Dhost.app.dir=<目录> -cp conf:lib/*:lib/extra/* <Main-Class>`，在后台运行（`setsid`），3 秒后进程还在才算启动成功。`java` 取 `$JAVA_HOME/bin/java`，没有设置时用 PATH 里的。`-Dhost.app.dir` 用来确认 `app.pid` 里的进程确实是这个程序，process-exporter 也按它区分进程。停止先发 SIGTERM，30 秒内没退出就 `kill -9`。

| `app.sh` 命令 | 作用 |
| --- | --- |
| `start`、`stop`、`restart` | 启停；已在运行时 start 直接返回 |
| `status` | 每行 `key=value`：`deployed`、`running`，运行中时还有 `pid`、`elapsed`（秒）、`rss`（KB）、`cpu_ms` |
| `log [文件] [行数]` | `logs/` 下 `server.log`、`error.log`、`console.log` 的最后几行 |
| `deploy <zip> [--overwrite-conf] [--keep 份数]` | 发布，见下文 |
| `backups`、`rollback <备份名>` | 回滚，见下文 |
| `conf-list`、`conf-get <文件>`、`conf-put <文件>` | 列出、读取、保存 `conf/` 下的文件（conf-put 从标准输入读内容） |

## 管理后台的配置

`conf/application.properties`：

| 配置 | 说明 |
| --- | --- |
| `ops.ssh.user`、`ops.ssh.key` | 登录服务器的账号（默认 root）和私钥，所有主机共用。公钥要加到每台服务器的 `/root/.ssh/authorized_keys` |
| `ops.script.dir` | `app.sh`、`host_init.sh` 所在目录，默认工作目录下的 `ops-scripts` |
| `ops.upload.dir` | 上传的程序包暂存目录 |
| `ops.backup.keep` | 发布时程序目录 `backup/` 下保留的旧版本数 |
| `ops.prometheus.targets-dir` | 写给 Prometheus 的主机列表目录（file_sd 的 `node.json`、`process.json`），启动时和主机增删改后重写；为空时不写 |
| `ops.exporter.node-port`、`ops.exporter.process-port` | 业务服务器上 node_exporter、process-exporter 的端口（默认 9100、9256）：「初始化」按它配置，写给 Prometheus 的主机列表按它生成 |

管理后台所在机器需要有 OpenSSH 客户端（`ssh` 命令）。第一次连接一台主机时自动记住它的指纹（`StrictHostKeyChecking=accept-new`），连接超时 10 秒。

## 权限

| 菜单 / 按钮 | 作用 |
| --- | --- |
| `ops_host` | 主机列表 |
| `ops_host:manage` | 添加、编辑、删除主机，测试连接，初始化 |
| `ops_app` | 程序列表、日志 |
| `ops_app:manage` | 新建、编辑、删除程序 |
| `ops_app:control` | 启动、停止、重启（「保存并重启」也需要） |
| `ops_app:deploy` | 上传并发布、回滚 |
| `ops_app:config` | 查看和保存 conf 下的文件（可能含密码，查看也需要这个按钮） |
| `ops_monitor` | 主机监控（嵌入的 Grafana 面板本身是匿名只读，不经过这里的权限） |

主机下拉选项（`/ops/host/options`）有 `ops_host`、`ops_app`、`ops_monitor` 之一即可；读取这次操作的输出（`/ops/task/get`）有 `ops_app` 或 `ops_host` 即可。

## 主机

### `POST /ops/host/list`

请求体 `{}`。每台主机通过一次 SSH 读取机器信息（CPU、网络取 1 秒内的变化），多台并行，结果缓存 10 秒。

返回 `list`，每项：
- `id`、`ip`、`sshPort`、`hostname`、`remark`
- `online`：能否通过 SSH 连上
- `initializedAt`：初始化成功的时间，0 表示还没有初始化
- `lastSeenAt`、`createdAt`
- `appCount`：绑定的程序数
- `runningCount`：正在运行的程序数（命令行带 `-Dhost.app.dir=` 的进程）
- `error`：连不上或读取失败时的原因
- `info`：连上且读取成功时返回

| `info` 字段 | 说明 |
| --- | --- |
| `hostname`、`os` | 系统主机名、操作系统 |
| `cpuCores`、`cpuUsage` | CPU 核数和使用率（0～1） |
| `memoryTotal`、`memoryUsed` | 内存，字节 |
| `disks` | `[{mount, total, used}]`，字节；同一块设备挂在多个位置时只取一个 |
| `uptime` | 开机时长，秒 |
| `netIn`、`netOut` | 网卡（不含回环）收发速率，字节/秒 |

### `POST /ops/host/options`

请求体 `{}`。不连接主机，在线状态取最近一次读取的结果。返回 `list`：`[{id, ip, hostname, remark, online}]`。

### `POST /ops/host/create`、`POST /ops/host/update`

```json
{"hostId":1,"ip":"10.0.0.11","sshPort":22,"remark":"机房 A"}
```

需要 `ops_host:manage`。`hostId` 只在 update 时填写。`ip` 为 IPv4 或主机名，`sshPort` 默认 22，`remark` 最长 128。IP 不能重复。

### `POST /ops/host/remove`

```json
{"hostId":1}
```

需要 `ops_host:manage`。只能删除没有绑定程序的主机，不改动服务器。

### `POST /ops/host/test`

```json
{"hostId":1}
```

需要 `ops_host:manage`。测试 SSH 连接，返回 `{message}`，例如 `连接成功：game-01 · Ubuntu 24.04.5 LTS`。

### `POST /ops/host/init`

```json
{"hostId":1}
```

需要 `ops_host:manage`。把 `host_init.sh` 送给服务器上的 `bash -s` 执行，返回 `{taskId}`。成功后记下 `initializedAt`。可以重复执行。
- apt 安装 `openjdk-21-jre-headless`、`unzip`。
- 监控：安装并配置 `prometheus-node-exporter`（9100）、`prometheus-process-exporter`（9256，按命令行里的 `-Dhost.app.dir=<目录>` 把进程归到程序），配置变了才重启。两个端口只需要对 Prometheus 所在机器开放。

## 程序

### `POST /ops/app/list`

```json
{"hostId":1}
```

`hostId` 为空时返回全部主机上的程序。每台主机通过一次 SSH 读出它上面所有程序的状态。返回 `list`，每项：
- 配置字段：`id`、`name`、`hostId`、`hostIp`、`hostname`、`dir`、`remark`、`deployedAt`（0 表示没有发布过）、`createdAt`、`updatedAt`
- `hostOnline`：读取状态成功
- `busy`：这台主机或这个程序上有操作正在执行
- `error`：连不上或读取失败时的原因
- `state`：读取成功时返回

| `state` 字段 | 说明 |
| --- | --- |
| `status` | `running`（运行中）、`stopped`（已停止）、`not-deployed`（`lib` 下还没有 jar）、`no-script`（目录里没有 `bin/app.sh`，发布时会拷过去） |
| `deployed` | `lib` 下有 jar |
| `running`、`pid` | 是否运行中、进程号 |
| `startTime` | 启动时间，毫秒 |
| `memory` | 常驻内存（RSS），字节 |
| `cpuUsage` | 两次查询之间的 CPU 使用率，0～1 |

### `POST /ops/app/create`、`POST /ops/app/update`

```json
{"appId":1,"name":"log","hostId":1,"dir":"/opt/danmu/log","remark":""}
```

需要 `ops_app:manage`。`appId` 只在 update 时填写。字段要求：
- `name`：字母、数字、`_ . -`，最长 64；同一台主机上不能重复
- `dir`：绝对路径，只能包含字母、数字、`_ . - /`，最长 256；同一台主机上不能重复
- `remark`：最长 128

新建时先把 `app.sh` 拷到 `<目录>/bin/`（目录不存在时创建），连不上主机就不保存。修改主机、目录时程序必须已停止，会把 `app.sh` 拷到新目录，旧目录不动。有操作正在执行时不能修改。

### `POST /ops/app/remove`

```json
{"appId":1}
```

需要 `ops_app:manage`。程序必须已停止，且没有操作正在执行。只删除登记，不改动服务器上的程序目录。

### `POST /ops/app/control`

```json
{"appId":1,"action":"restart"}
```

需要 `ops_app:control`。`action` 为 `start`、`stop`、`restart`，返回 `{taskId}`。执行 `bin/app.sh <action>`；启动失败时输出 `logs/console.log` 的最后 30 行。

### `POST /ops/app/log`

```json
{"appId":1,"lines":200,"file":"server.log"}
```

返回 `{content}`：`logs/<file>` 的最后 `lines` 行。`file` 为 `server.log`（全部日志，默认）、`error.log`（只有 ERROR）或 `console.log`（最近一次启动的控制台输出）；默认 200，上限 2000，最多读取末尾 1MB。

### `POST /ops/app/config/list`、`/ops/app/config/get`、`/ops/app/config/save`

```json
{"appId":1}
{"appId":1,"file":"application.properties"}
{"appId":1,"file":"application.properties","content":"...","restart":false}
```

需要 `ops_app:config`。`file` 为相对 `conf` 的路径，不能跳出 `conf`；单个文件不超过 1MB，按 UTF-8 读写。分别执行 `app.sh conf-list`、`conf-get`、`conf-put`。返回值：
- list：`{files}`
- get：`{content}`
- save：`{taskId}`。旧文件先备份到程序目录的 `backup-conf/<时间>/`；`restart` 为 true 时保存后重启（还需要 `ops_app:control`）

## 发布和回滚

需要 `ops_app:deploy`。程序包是 `lib`、`conf`、`excel` 打成的 zip，外面可以再包一层目录；包里有 `bin` 也不使用。

1. `POST /ops/app/upload/start`，`{"appId":1}`，返回 `{uploadId}`。
2. `POST /ops/app/upload/chunk`，`{"appId":1,"uploadId":"...","index":0,"data":"base64"}`。`index` 从 0 开始按顺序发送；前端每片 2MB，一片不能超过 4MB（HTTP 请求体上限 16MB）。重发上一片（应答丢失时）会被忽略。程序包先存在管理后台本机的 `ops.upload.dir`，最大 1GB，1 小时没有新分片的被删除。
3. `POST /ops/app/deploy`，`{"appId":1,"uploadId":"...","overwriteConf":false}`，返回 `{taskId}`：
   - 把最新的 `app.sh` 拷到 `<目录>/bin/`，程序包传到服务器的 `/tmp`，然后执行 `app.sh deploy <zip> --keep <ops.backup.keep> [--overwrite-conf]`，结束后删除 zip。
   - 解压，确认包里有 `lib`、恰好一个 Main-Class；不对就直接失败，不停止正在运行的程序。
   - 在运行的先停止。备份机器上的 `lib`、`conf`、`excel` 到 `backup/<时间>`，只保留 `ops.backup.keep` 份：要替换的目录移进备份，不替换的复制一份。替换中途失败时恢复原来的版本。
   - 只替换包里有的目录。`conf` 默认不替换；目录里没有 conf，或 `overwriteConf` 为 true 时使用包里的。
   - 原来在运行的重新启动。

回滚：
- `POST /ops/app/backups`，`{"appId":1}`，返回 `{list}`：`backup/` 下的版本名（`yyyyMMdd-HHmmss`），新的在前。
- `POST /ops/app/rollback`，`{"appId":1,"backup":"20261106-074714"}`，返回 `{taskId}`。执行 `app.sh rollback <备份名>`：备份里有的 `lib`、`conf`、`excel` 换回去（conf 也换回发布那一刻的版本）；当前这些目录先移入 `backup/<现在时间>`，所以回滚本身也能再回滚。原来在运行的回滚后重新启动。

## 主机监控

### `POST /ops/app/options`

```json
{"hostId":1}
```

需要 `ops_monitor` 或 `ops_app`。返回 `list`：`[{id, name, dir, remark}]`，「主机监控」页的程序下拉选项。

曲线不经过管理后台：页面嵌入 Grafana 的面板 `/grafana/d/danmu-host/host?var-host=<IP>`（主机），选中程序时为 `/grafana/d/danmu-app/app?var-host=<IP>&var-app=<程序目录>`。

## 执行输出

没有历史列表。初始化、启停、发布、回滚、保存配置在结束时写入操作日志（操作名、操作人、成功或失败；参数里有 `title`、`hostId`、`appId`）。请求当时就失败的（参数不合法、已有操作在执行）仍在请求时记录。

### `POST /ops/task/get`

```json
{"taskId":1,"offset":0}
```

返回这次操作的字段，加上 `output`（`offset` 之后的输出）和 `offset`（下次读取用）。页面每秒读取一次直到 `status` 不是 `running`。输出最多保留 20 万字符。管理后台重启时，还在执行中的操作记为失败，并写入操作日志。

## 错误码

| 码 | 含义 |
| --- | --- |
| 100068 | 主机不存在 |
| 100071 | 主机还有程序绑定，不能删除 |
| 100072 | 程序不存在 |
| 100073 | 这台主机上已有程序使用该目录 |
| 100074 | 程序配置不合法，message 里是原因 |
| 100075 | 程序正在运行，请先停止 |
| 100076 | 执行远程命令失败（连不上主机、命令失败），message 里是原因 |
| 100077 | 该 IP 的主机已存在 |
| 100078 | 主机配置不合法 |
| 100079 | 这台主机上已有同名程序 |
| 100080 | 有任务正在执行，请等它结束 |
| 100081 | 任务不存在 |
| 100082 | 上传不存在或已过期，请重新上传 |

100069、100070 已不再使用（原来的 host 服务）。

## 已有库升级

`schema.sql` 只在首次建库时执行。已有的库执行 `src/main/resources/db/migration/` 下还没执行过的脚本，按文件名顺序：

- `20261103-ops-center.sql`：运维中心的主机、程序表和菜单（运维中心之前建的库）。
- `20261106-ops-ansible.sql`：主机加 SSH 端口和初始化时间、程序名称唯一、执行记录表和「运维任务」菜单。
- `20261108-drop-ops-task-menu.sql`：去掉「运维任务」菜单。表 `ops_task` 保留，只给正在执行的操作写输出。
