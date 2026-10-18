# admin-agent

服务器管理的 Agent：装在每台业务服务器上，托管业务进程（每个进程一个 systemd 服务 `adm-<进程名>`）、执行后台下发的启动 / 停止 / 重启 / 删除指令、每 5 秒上报主机和进程指标。设计和接口见 `../docs/server-management.md`。

- Java 17+，单个 jar（`mvn package` 生成 `target/admin-agent.jar`），依赖 OSHI（指标）和 Jackson。
- 安装：在后台「服务器管理 → 服务器」生成安装命令，在服务器上以 root 执行；安装脚本是本目录的 `install.sh`。
- 发布：`agent/deploy.sh` 打包并把 jar 和 `install.sh` 上传到 gz 的 `/opt/admin-agent-dist`，由 nginx 在 `/agent/` 下提供。已安装的 Agent 不会自动升级，重新执行安装命令即可。
- 当前版本 0.4.0：后台下发的进程定义带 `env`（`ADM_INSTANCE_ID`、`ADM_REPORT_SECRET`）时，写入 `/etc/admin-agent/env/adm-<进程名>.env`（目录不存在则创建，权限 600），再补上 `ADM_REPORT_URL=<agent.properties 的 baseUrl>/api/node/report`；内容变化时与服务文件一样 `daemon-reload`，进程重启后生效。删除进程时同时删除该文件。旧版 Agent 不认识 `env`，服务文件里的 `EnvironmentFile=-` 带 `-`，进程照常启动，只是不上报。
- 服务器上的文件：`/opt/admin-agent/admin-agent.jar`、`agent.properties`（后台地址、服务器 ID、密钥，权限 600）、`state.properties`（各进程最近一次启动时的配置版本）、`/etc/admin-agent/env/adm-*.env`（各进程注入的环境变量，权限 600）；服务 `admin-agent`，日志 `journalctl -u admin-agent`。
- 卸载：`systemctl disable --now admin-agent && rm -rf /opt/admin-agent /etc/admin-agent /etc/systemd/system/admin-agent.service && systemctl daemon-reload`（托管进程的 `adm-*` 服务需要先在后台删除）。
