# 开发测试环境

Redis 和 PostgreSQL 运行在 SSH 别名为 `us` 的远程服务器 Docker 中。客户端通过服务器公网 IP `49.51.199.110` 直接访问，不再使用 SSH 隧道。

## 远程容器

| 服务 | 容器名称 | 公网地址 | 持久化卷 |
| --- | --- | --- | --- |
| Redis | `deep-sea-war-redis` | `49.51.199.110:16379` | `deep-sea-war-redis-data` |
| PostgreSQL | `deep-sea-war-postgres` | `49.51.199.110:15432` | `deep-sea-war-postgres-data` |

两个容器均将端口发布到宿主机的所有 IPv4 接口，并配置了 `--restart unless-stopped`。远程服务器或 Docker 重启后，容器会自动启动。

## 客户端连接

本地程序和 IDE 数据源使用以下地址：

| 服务 | 地址 |
| --- | --- |
| Redis | `49.51.199.110:16379` |
| PostgreSQL | `49.51.199.110:15432` |

数据库名称和用户名为 `deep_sea_war`。完整连接参数和测试密码保存在 `admin-server/conf/application.properties` 中。

## 远程容器管理

查看状态：

```shell
ssh us 'docker ps -a --filter name=deep-sea-war'
```

启动容器：

```shell
ssh us 'docker start deep-sea-war-redis deep-sea-war-postgres'
```

停止容器：

```shell
ssh us 'docker stop deep-sea-war-redis deep-sea-war-postgres'
```

远程凭证备份位于：

```text
/opt/deep-sea-war/redis.password
/opt/deep-sea-war/postgres.password
```

文件权限为 `600`，不要将密码写入公开文档或提交到公共仓库。
