# 数据备份

在「系统管理 → 系统设置 → 数据备份」配置，备份 admin-background 自己的 PostgreSQL 数据库。不接对象存储，文件保存在服务器本地。

- 实现：`BackupService` 调用 `pg_dump -Fc`（自定义格式，压缩）写到 `backup.dir`，文件名 `admin_background-yyyyMMdd-HHmmss.dump`。先写 `.part` 再改名，失败不留半成品。同一时间只执行一个备份，超时 30 分钟。
- 配置：`application.properties` 的 `backup.dir`（运行账号可写）、`backup.pg-dump`（pg_dump 路径）；`system_setting` 的 `backup.enabled`、`backup.time`（HH:mm，服务器时间）、`backup.keep`（1～90，默认 7），最近一次结果 `backup.lastTime`、`backup.lastSuccess`、`backup.lastMessage`。
- 定时：zfoo `@Scheduler` 每分钟检查一次，到设定时间且当天未触发过就备份；每次成功后按保留份数删除最旧的备份（手动备份也计入）。
- `pg_dump` 版本不能低于数据库版本（例如数据库 16、Debian 13 只有 `postgresql-client-17` 也可以）。安装和恢复见 `deploy/README.md`。

## 接口

| 接口 | 权限 | 请求 | 返回 |
| --- | --- | --- | --- |
| `POST /system/backup/get` | 平台 `manage_setting` | `{}` | 设置、`running`、最近一次结果、`files`（`name`、`size`、`createdAt`，按时间倒序） |
| `POST /system/backup/update` | `manage_setting:backup` | `{ enabled, time, keep }` | 同 get |
| `POST /system/backup/run` | `manage_setting:backup` | `{}` | 同 get；正在备份时返回 `100051` |
| `POST /system/backup/download` | `manage_setting:backup` | `{ name }` | `{ name, data }`，data 为 base64；超过 50MB 返回 `100053` |
| `POST /system/backup/remove` | `manage_setting:backup` | `{ name }` | `null` |

文件名必须符合备份文件名格式，否则返回 `100001`；文件不存在返回 `100052`。修改设置、立即备份、下载、删除都记入操作日志。
