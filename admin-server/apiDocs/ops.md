# 运维中心

管理服务器和服务器上的程序。管理后台调用本机的 `ssh` 命令，以 root 登录服务器执行命令。

- **程序**：保存在 `ops_app`，每条是一台机器（IP、SSH 端口）上的一个目录（绝对路径，自己配置，例如 `/usr/local/games/game1`）。同一个 IP 上名称、目录都不能重复。没有单独的主机管理。
  - 机器上的运行环境（java、unzip）由使用者自己准备，管理后台不安装任何东西。
  - 每个项目都有自己的 `bin/app.sh`（内容相同），和 `conf` 一样随 `mvn package` 打进程序包、随发布进入程序目录。它从自己所在位置推导程序目录，不需要参数或配置。
  - 启动、关闭、重启、发布都是通过 SSH 执行 `bash <目录>/bin/app.sh <命令>`；在服务器上手动执行 `<目录>/bin/app.sh start|stop|restart|status` 效果一样。没有 systemd，也没有自动拉起。
- **后台执行**：启停、发布在后台执行，接口立即返回 `taskId`，发起操作的页面用 `/ops/task/get` 读实时输出。同一个程序同一时间只能有一个。谁做了什么、成功还是失败，在结束时写入操作日志。

管理后台自己也用 `bin/app.sh` 启停。

## 程序目录的格式

`app.sh` 只认这个格式：

| 路径 | 说明 |
| --- | --- |
| `bin/app.sh` | 管理脚本，随程序包发布 |
| `lib/` | jar，其中恰好一个 jar 的 MANIFEST 带 `Main-Class`；`lib/extra/` 也在 classpath 上 |
| `conf/` | 配置文件，放在 classpath 最前面；`conf/jvm.options` 为 JVM 参数（每行一个，`#` 开头和空行忽略，可以没有） |
| `excel/` | 可选 |
| `logs/` | 程序自己写的日志；`console.log` 为最近一次启动的控制台输出（JVM 起不来时看这里） |
| `app.pid` | 运行中的进程号 |
| `backup/<时间>/` | 发布前的 `bin`、`lib`、`conf`、`excel` |

启动命令：`cd <目录> && java <jvm.options> -Dhost.app.dir=<目录> -cp conf:lib/*:lib/extra/* <Main-Class>`，在后台运行（`setsid`），3 秒后进程还在才算启动成功。`java` 取 `$JAVA_HOME/bin/java`，没有设置时用 PATH 里的。`-Dhost.app.dir` 由 `app.sh` 自动加上，用来确认 `app.pid` 里的进程确实是这个程序。停止先发 SIGTERM，30 秒内没退出就 `kill -9`。

| `app.sh` 命令 | 作用 |
| --- | --- |
| `start`、`stop`、`restart` | 启停；已在运行时 start 直接返回 |
| `status` | 每行 `key=value`：`deployed`、`running`，运行中时还有 `pid`、`elapsed`（秒） |
| `deploy <程序目录> [--overwrite-conf] [--keep 份数]` | 在解压出来的新包里执行，把这个包发布到程序目录，见下文 |
| `log`、`backups`、`rollback`、`conf-list`、`conf-get`、`conf-put` | 只在服务器上手动使用，管理后台不调用 |

## SSH

执行的是 `ssh -p <端口> root@<IP>`，和在管理机上手动执行一样：用运行管理后台的用户的默认私钥（`~/.ssh/`）和 `~/.ssh/config`，公钥要加到每台服务器的 `/root/.ssh/authorized_keys`。第一次连接一台机器时自动记住它的指纹（`StrictHostKeyChecking=accept-new`），连接超时 10 秒。管理机上的程序也一样登记（填管理机的内网 IP），SSH 连自己。

## 权限

| 菜单 / 按钮 | 作用 |
| --- | --- |
| `ops_app` | 程序列表 |
| `ops_app:manage` | 新建、编辑、删除程序 |
| `ops_app:control` | 启动、关闭、重启 |
| `ops_app:deploy` | 上传并发布 |

读取这次操作的输出（`/ops/task/get`）需要 `ops_app`。

## 程序

### `POST /ops/app/list`

```json
{"ip":"10.0.0.11"}
```

`ip` 为空时返回全部程序。每台机器（IP、端口）通过一次 SSH 读出它上面所有程序的状态（`bin/app.sh status`），各台并行。返回：
- `ips`：所有程序用到的 IP，页面的筛选下拉框用
- `list`，每项：
  - 配置字段：`id`、`name`、`ip`、`sshPort`、`dir`、`remark`、`deployedAt`（0 表示没有发布过）、`createdAt`、`updatedAt`
  - `hostOnline`：读取状态成功
  - `busy`：这个程序上有操作正在执行
  - `error`：连不上或读取失败时的原因
  - `state`：读取成功时返回

| `state` 字段 | 说明 |
| --- | --- |
| `status` | `running`（运行中）、`stopped`（已关闭）、`not-deployed`（还没有发布：没有 `bin/app.sh` 或 `lib` 下没有 jar） |
| `deployed` | `lib` 下有 jar |
| `running`、`pid` | 是否运行中、进程号 |
| `startTime` | 启动时间，毫秒 |

### `POST /ops/app/create`、`POST /ops/app/update`

```json
{"appId":1,"name":"log","ip":"10.0.0.11","sshPort":22,"dir":"/opt/danmu/log","remark":""}
```

需要 `ops_app:manage`。`appId` 只在 update 时填写。字段要求：
- `name`：字母、数字、`_ . -`，最长 64；同一个 IP 上不能重复
- `ip`：IPv4 或主机名；`sshPort` 默认 22
- `dir`：绝对路径，只能包含字母、数字、`_ . - /`，最长 256；同一个 IP 上不能重复
- `remark`：最长 128

新增时，以及修改了 IP 或端口时，先 SSH 连一次（`ssh root@<IP> true`），连不上返回 100076，不保存。只保存记录，目录在第一次发布时创建；修改不动服务器上的文件。有操作在执行时不能修改。

### `POST /ops/app/remove`

```json
{"appId":1}
```

需要 `ops_app:manage`。先 SSH 读一次状态：运行中返回 100084，要先关闭；连不上返回 100076（无法确认是否已关闭）；有操作在执行时返回 100080。只删除记录，不停止进程、不删除服务器上的文件。

### `POST /ops/app/control`

```json
{"appId":1,"action":"restart"}
```

需要 `ops_app:control`。`action` 为 `start`（启动）、`stop`（关闭）、`restart`（重启），返回 `{taskId}`。执行 `bin/app.sh <action>`；启动失败时输出 `logs/console.log` 的最后 30 行。

## 发布

需要 `ops_app:deploy`。程序包是 `mvn package` 产物（`bin`、`lib`、`conf`、`excel`）打成的 zip，必须带 `bin/app.sh`，外面可以再包一层目录。

1. `POST /ops/app/upload/start`，`{"appId":1}`，返回 `{uploadId}`。
2. `POST /ops/app/upload/chunk`，`{"appId":1,"uploadId":"...","index":0,"data":"base64"}`。`index` 从 0 开始按顺序发送；前端每片 2MB，一片不能超过 4MB（HTTP 请求体上限 16MB）。重发上一片（应答丢失时）会被忽略。程序包先存在管理后台目录下的 `upload/`，最大 1GB，1 小时没有新分片的被删除。
3. `POST /ops/app/deploy`，`{"appId":1,"uploadId":"...","overwriteConf":false}`，返回 `{taskId}`：
   - 程序包传到服务器的 `/tmp` 并解压，执行**新包里的** `bin/app.sh deploy <程序目录> [--overwrite-conf]`，结束后删除临时文件。
   - 确认新包里恰好一个 Main-Class；不对就直接失败，不停止正在运行的程序。
   - 在运行的先停止。备份机器上的 `bin`、`lib`、`conf`、`excel` 到 `backup/<时间>`，只保留 3 份：要替换的目录移进备份，不替换的复制一份。替换中途失败时恢复原来的版本。
   - 只替换包里有的目录。`conf` 默认不替换；目录里没有 conf，或 `overwriteConf` 为 true 时使用包里的。
   - 原来在运行的重新启动。

## 执行输出

没有历史列表。启停、发布在结束时写入操作日志（操作名、操作人、成功或失败；参数里有 `title`、`appId`）。请求当时就失败的（参数不合法、已有操作在执行）仍在请求时记录。

### `POST /ops/task/get`

```json
{"taskId":1,"offset":0}
```

返回这次操作的字段，加上 `output`（`offset` 之后的输出）和 `offset`（下次读取用）。页面每秒读取一次直到 `status` 不是 `running`。输出最多保留 20 万字符。管理后台重启时，还在执行中的操作记为失败，并写入操作日志。

## 错误码

| 码 | 含义 |
| --- | --- |
| 100072 | 程序不存在 |
| 100073 | 这个 IP 上已有程序使用该目录 |
| 100074 | 程序配置不合法，message 里是原因 |
| 100076 | 执行远程命令失败（连不上机器、命令失败），message 里是原因 |
| 100079 | 这个 IP 上已有同名程序 |
| 100080 | 有任务正在执行，请等它结束 |
| 100081 | 任务不存在 |
| 100082 | 上传不存在或已过期，请重新上传 |
| 100084 | 程序正在运行，请先关闭再删除 |

100068～100071、100075、100077、100078、100083 已不再使用。
