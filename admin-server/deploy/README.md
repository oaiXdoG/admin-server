# admin-server 部署

本文只说明 admin-server 怎么打包、产物有哪些、怎么启动，与具体服务器无关。

## 运行依赖

| 依赖 | 说明 |
| --- | --- |
| JDK 21 | 编译和运行都需要 |
| PostgreSQL | 库 `admin_background`，表结构见 `src/main/resources/db/schema.sql` |
| Redis | 会话、登录失败计数 |
| `pg_dump` | 可选，数据备份用，版本不能低于数据库版本 |

## 打包

在 `admin-server/` 目录执行：

```bash
mvn clean package dependency:copy-dependencies -DskipTests -DincludeScope=runtime -DoutputDirectory=target/lib
```

## 产物

| 产物 | 说明 |
| --- | --- |
| `target/admin-server.jar` | 程序本身（不含依赖，也不含配置文件） |
| `target/lib/*.jar` | 运行时依赖 |
| `conf/application.properties` | 环境配置：端口、Redis、PostgreSQL、密钥等，每个环境各自维护 |
| `conf/config.xml`、`conf/protocol.xml`、`conf/logback.xml` | 程序配置（Spring 容器、协议、日志），与环境无关，随版本发布 |
| `src/main/resources/db/schema.sql` | 完整表结构，已打进 jar，首次安装时手工执行 |

配置文件不打进 jar，启动时通过 classpath 加载。

## 发布目录

把产物组装成下面的目录，放到任意位置：

```text
admin-server/
├── lib/     admin-server.jar + target/lib/ 下的全部 jar
└── conf/    application.properties、config.xml、protocol.xml、logback.xml
```

升级时整体替换 `lib/`，覆盖 `config.xml`、`protocol.xml`、`logback.xml`；`application.properties` 保留现场的那份，新版本加了配置项时再补上。

## 配置

`conf/application.properties`：

| 配置项 | 必填 | 说明 |
| --- | --- | --- |
| `server.host` / `server.port` | 是 | HTTP 监听地址，默认 `0.0.0.0:9050` |
| `redis.host` / `redis.port` / `redis.password` / `redis.database` | 是 | Redis |
| `database.postgresql.url` / `.username` / `.password` | 是 | PostgreSQL |
| `database.postgresql.hikari.maximum-pool-size` / `.minimum-idle` / `.connection-timeout` | 是 | 连接池 |
| `security.secret-key` | 是 | 用来加密保存敏感配置。每个环境单独生成，生成后不要修改（改了之后，已经保存的密码要在系统设置里重新填） |
| `backup.dir` | 否 | 备份目录，默认 `backups` |
| `backup.pg-dump` | 否 | `pg_dump` 路径，默认 `pg_dump` |
| `admin.init.account` / `admin.init.password` | 否 | 初始超级管理员，只在一个账号都没有时生效。密码为空时会随机生成，并以 WARN 级别打印到日志 |

相对路径都以启动时的工作目录为基准，所以运行账号需要对这些目录有写权限。

## 首次安装

1. 在 PostgreSQL 中创建库 `admin_background`，执行 `schema.sql`。
2. 按上一节填写 `conf/application.properties`。
3. 启动（见下节）。启动后用初始管理员登录。

## 启动与停止

在发布目录下执行：

```bash
java -Xms256m -Xmx512m -cp "conf:lib/*" com.oaixdog.adminbackground.Application
```

- `conf` 必须放在 classpath 的最前面，所有配置文件都从这里读取。
- 日志只输出到标准输出，由外层的进程管理工具负责收集。
- 停止时发送 `SIGTERM`，程序会通过 shutdown hook 正常退出，退出码是 143。
- 程序以前台方式运行，需要守护进程或开机自启时，交给 systemd、supervisor、Docker 等托管。
