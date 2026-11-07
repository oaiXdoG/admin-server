# 运维中心

管理服务器和服务器上的程序。管理后台用 **Ansible** 以 root 通过 SSH 操作服务器，程序在服务器上由 **systemd** 运行。

- **主机**：在后台手动添加（IP、SSH 端口），保存在 `ops_host`。添加后点「初始化」安装程序运行需要的 JDK 21、unzip。
- **程序**：保存在 `ops_app`，每条绑定一台主机和它上面的一个目录（绝对路径，自己配置）。
  - 名称同时是 systemd 服务名 `danmu-<名称>`，同一台主机上名称、目录都不能重复。
  - 服务在第一次启动或发布时由 Ansible 生成：执行程序目录里的 `bin/start.sh --foreground`（JVM 参数在 `conf/jvm.options`），挂了自动拉起、开机自启；stdout 丢弃，stderr 进 journal。
  - 在服务器上手动启停：`systemctl start|stop|restart danmu-<名称>`；JVM 起不来时看 `journalctl -u danmu-<名称>`。
- **运维任务**：初始化、启停、发布、回滚、保存配置都是运维任务（`ops_task`），接口立即返回 `taskId`，用 `/ops/task/get` 读取实时输出。同一个程序同一时间只能有一个任务。
- **监控**：主机「初始化」时装上 node_exporter、process-exporter，由 Prometheus 拉取；「主机监控」页嵌入 Grafana 的面板（经 nginx 以同源的 `/grafana/` 访问）。管理后台只负责把主机列表写给 Prometheus（`ops.prometheus.targets-dir`）和提供下拉选项。

playbook 在 admin-server 的 `ansible/` 目录，随程序一起打包；统一的 `bin` 脚本在 danmu 根目录的 `ops/bin`，Prometheus、Grafana 的配置和面板在 `ops/monitoring`，见 `ops/README.md`。

管理后台自己不登记到运维中心：它和其他服务一样用 `mvn package` 打出 `target/admin-server`（bin、conf、lib、ansible），在服务器上手动执行 `bin/start.sh`、`bin/stop.sh` 启停。

## 管理后台的配置

`conf/application.properties`：

| 配置 | 说明 |
| --- | --- |
| `ops.ssh.user`、`ops.ssh.key` | 登录服务器的账号（默认 root）和私钥，所有主机共用。公钥要加到每台服务器的 `/root/.ssh/authorized_keys` |
| `ops.ansible.dir` | playbook 目录，默认工作目录下的 `ansible` |
| `ops.upload.dir` | 上传的程序包暂存目录 |
| `ops.backup.keep` | 发布时程序目录 `backup/` 下保留的旧版本数 |
| `ops.prometheus.targets-dir` | 写给 Prometheus 的主机列表目录（file_sd 的 `node.json`、`process.json`），启动时和主机增删改后重写；为空时不写 |
| `ops.exporter.node-port`、`ops.exporter.process-port` | 业务服务器上 node_exporter、process-exporter 的端口（默认 9100、9256）：「初始化」按它配置，写给 Prometheus 的主机列表按它生成 |

管理后台所在机器（管理机）由 danmu 的 `ops/deploy.sh` 部署，以上配置都由它的环境文件生成，见 `ops/README.md`。第一次连接一台主机时自动记住它的指纹（`StrictHostKeyChecking=accept-new`）。

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
| `ops_task` | 运维任务列表 |
| `ops_monitor` | 主机监控（嵌入的 Grafana 面板本身是匿名只读，不经过这里的权限，见 `ops/README.md`） |

主机下拉选项（`/ops/host/options`）有 `ops_host`、`ops_app`、`ops_monitor` 之一即可；读取单个任务（`/ops/task/get`）有 `ops_task`、`ops_app`、`ops_host` 之一即可。

## 主机

### `POST /ops/host/list`

请求体 `{}`。每台主机通过一次 SSH 读取机器信息（CPU、网络取 1 秒内的变化），多台并行，结果缓存 10 秒。

返回 `list`，每项：
- `id`、`ip`、`sshPort`、`hostname`、`remark`
- `online`：能否通过 SSH 连上
- `initializedAt`：初始化成功的时间，0 表示还没有初始化
- `lastSeenAt`、`createdAt`
- `appCount`：绑定的程序数
- `runningCount`：正在运行的 `danmu-*` 服务数
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

需要 `ops_host:manage`。执行 `init.yml`，返回 `{taskId}`。成功后记下 `initializedAt`。可以重复执行。
- apt 安装 `openjdk-21-jre-headless`、`unzip`。
- 监控：安装并配置 `prometheus-node-exporter`（9100，开启 systemd 收集器，只报告 `danmu-*` 服务）、`prometheus-process-exporter`（9256，按命令行里的 `-Dhost.app.dir=<目录>` 把进程归到程序）。两个端口只需要对 Prometheus 所在机器开放。

## 程序

### `POST /ops/app/list`

```json
{"hostId":1}
```

`hostId` 为空时返回全部主机上的程序。每台主机通过一次 SSH 读出它上面所有程序的状态。返回 `list`，每项：
- 配置字段：`id`、`name`、`hostId`、`hostIp`、`hostname`、`dir`、`remark`、`deployedAt`（0 表示没有发布过）、`createdAt`、`updatedAt`
- `hostOnline`：读取状态成功
- `busy`：有运维任务正在执行
- `error`：连不上或读取失败时的原因
- `state`：读取成功时返回

| `state` 字段 | 说明 |
| --- | --- |
| `deployed` | `lib` 下有 jar |
| `status` | systemd 的 ActiveState：`active`、`inactive`、`failed`、`activating` 等；服务还没生成时为 `not-found` |
| `running`、`pid` | 是否运行中、进程号 |
| `unmanaged` | 运行中但不归 systemd 管（用 `bin/start.sh` 在后台启动的，例如以前的 host 启动的）；下次启动、发布时会先用 `bin/stop.sh` 停掉再交给 systemd |
| `restarts` | systemd 自动拉起的次数 |
| `startTime` | 启动时间，毫秒 |
| `memory` | 内存（systemd 统计的 cgroup 内存），字节 |
| `cpuUsage` | 两次查询之间的 CPU 使用率，0～1 |

### `POST /ops/app/create`、`POST /ops/app/update`

```json
{"appId":1,"name":"log","hostId":1,"dir":"/opt/danmu/log","remark":""}
```

需要 `ops_app:manage`。`appId` 只在 update 时填写。字段要求：
- `name`：字母、数字、`_ . -`，最长 64；同一台主机上不能重复
- `dir`：绝对路径，只能包含字母、数字、`_ . - /`，最长 256；同一台主机上不能重复
- `remark`：最长 128

只保存登记，不连接服务器。修改名称、主机、目录时程序必须已停止；名称或主机变了会删除旧主机上旧名称的 systemd 服务。

### `POST /ops/app/remove`

```json
{"appId":1}
```

需要 `ops_app:manage`。程序必须已停止。删除登记和 systemd 服务，不删除程序目录。

### `POST /ops/app/control`

```json
{"appId":1,"action":"restart"}
```

需要 `ops_app:control`。`action` 为 `start`、`stop`、`restart`，返回 `{taskId}`。执行 `app_control.yml`：
1. 生成（或更新）systemd 服务。
2. 启动、重启时先接管不归 systemd 管的旧进程。
3. `systemctl start|stop|restart danmu-<名称>`。
4. 启动、重启后观察 5 秒：状态仍是 active 且没有被自动拉起才算成功；失败时输出 journal 的最后 30 行。

### `POST /ops/app/log`

```json
{"appId":1,"lines":200,"file":"server.log"}
```

返回 `{content}`：程序 logback 写入的 `logs/<file>` 的最后 `lines` 行。`file` 为 `server.log`（全部日志，默认）或 `error.log`（只有 ERROR）；默认 200，上限 2000，最多读取末尾 1MB。

### `POST /ops/app/config/list`、`/ops/app/config/get`、`/ops/app/config/save`

```json
{"appId":1}
{"appId":1,"file":"application.properties"}
{"appId":1,"file":"application.properties","content":"...","restart":false}
```

需要 `ops_app:config`。`file` 为相对 `conf` 的路径，不能跳出 `conf`；单个文件不超过 1MB，按 UTF-8 读写。返回值：
- list：`{files}`
- get：`{content}`
- save：`{taskId}`。旧文件先备份到程序目录的 `backup-conf/<时间>/`；`restart` 为 true 时保存后重启（还需要 `ops_app:control`）

## 发布和回滚

需要 `ops_app:deploy`。程序包是 `mvn package` 产物目录（`bin`、`conf`、`lib`、`excel`）打成的 zip，必须带 `bin/start.sh`，外面可以再包一层目录。

1. `POST /ops/app/upload/start`，`{"appId":1}`，返回 `{uploadId}`。
2. `POST /ops/app/upload/chunk`，`{"appId":1,"uploadId":"...","index":0,"data":"base64"}`。`index` 从 0 开始按顺序发送；前端每片 2MB，一片不能超过 4MB（HTTP 请求体上限 16MB）。重发上一片（应答丢失时）会被忽略。程序包先存在管理后台本机的 `ops.upload.dir`，最大 1GB，1 小时没有新分片的被删除。
3. `POST /ops/app/deploy`，`{"appId":1,"uploadId":"...","overwriteConf":false}`，返回 `{taskId}`。执行 `app_deploy.yml`：
   - 上传到服务器的临时目录，解压校验。
   - 生成 systemd 服务，接管旧进程；在运行的先停止。
   - 旧的 `bin`、`lib`、`excel` 移入 `backup/<时间>`，只保留 `ops.backup.keep` 份。
   - `conf` 默认保留机器上的；目录里没有 conf，或 `overwriteConf` 为 true 时使用包里的（旧 conf 一起备份）。
   - 原来在运行的重新启动，并检查启动结果。

回滚：
- `POST /ops/app/backups`，`{"appId":1}`，返回 `{list}`：`backup/` 下的版本名（`yyyyMMdd-HHmmss`），新的在前。
- `POST /ops/app/rollback`，`{"appId":1,"backup":"20261106-074714"}`，返回 `{taskId}`。当前的 `bin`、`lib`、`excel`（以及备份里有的 `conf`）先移入 `backup/<现在时间>`，再换回这个版本，所以回滚本身也能再回滚；原来在运行的回滚后重新启动。

## 主机监控

### `POST /ops/app/options`

```json
{"hostId":1}
```

需要 `ops_monitor` 或 `ops_app`。返回 `list`：`[{id, name, dir, remark}]`，「主机监控」页的程序下拉选项。

曲线不经过管理后台：页面嵌入 Grafana 的面板 `/grafana/d/danmu-host/host?var-host=<IP>`（主机），选中程序时为 `/grafana/d/danmu-app/app?var-host=<IP>&var-app=<程序目录>`。

## 运维任务

### `POST /ops/task/list`

```json
{"page":1,"pageSize":20,"hostId":null,"appId":null}
```

按开始时间倒序。返回 `{items, total}`，每项：`id`、`type`（`init`、`start`、`stop`、`restart`、`deploy`、`rollback`、`config`）、`title`、`hostId`、`appId`、`account`（操作人）、`status`（`running`、`success`、`failed`）、`startedAt`、`finishedAt`。

### `POST /ops/task/get`

```json
{"taskId":1,"offset":0}
```

返回任务字段，加上 `output`（`offset` 之后的输出）和 `offset`（下次读取用）。页面每秒读取一次直到 `status` 不是 `running`。输出最多保留 20 万字符。管理后台重启时，还在执行中的任务记为失败。

## 错误码

| 码 | 含义 |
| --- | --- |
| 100068 | 主机不存在 |
| 100071 | 主机还有程序绑定，不能删除 |
| 100072 | 程序不存在 |
| 100073 | 这台主机上已有程序使用该目录 |
| 100074 | 程序配置不合法，message 里是原因 |
| 100075 | 程序正在运行，请先停止 |
| 100076 | 执行 Ansible 失败（连不上主机、命令失败），message 里是原因 |
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
- `20261106-ops-ansible.sql`：主机加 SSH 端口和初始化时间、程序名称唯一、运维任务表和「运维任务」菜单。
