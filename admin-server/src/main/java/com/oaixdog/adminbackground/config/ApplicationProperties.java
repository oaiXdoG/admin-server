package com.oaixdog.adminbackground.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Getter
@Component
public class ApplicationProperties {

    @Value("${server.host}")
    private String host;

    @Value("${server.port}")
    private int port;

    @Value("${redis.host}")
    private String redisHost;

    @Value("${redis.port}")
    private int redisPort;

    @Value("${redis.password}")
    private String redisPassword;

    @Value("${redis.database}")
    private int redisDatabase;

    @Value("${database.postgresql.url}")
    private String postgresqlUrl;

    @Value("${database.postgresql.username}")
    private String postgresqlUsername;

    @Value("${database.postgresql.password}")
    private String postgresqlPassword;

    @Value("${database.postgresql.hikari.maximum-pool-size}")
    private int postgresqlMaximumPoolSize;

    @Value("${database.postgresql.hikari.minimum-idle}")
    private int postgresqlMinimumIdle;

    @Value("${database.postgresql.hikari.connection-timeout}")
    private long postgresqlConnectionTimeout;

    /** 系统里还没有任何账号时，启动时创建的初始超级管理员；密码为空则随机生成并打印到日志 */
    @Value("${admin.init.account:admin}")
    private String adminInitAccount;

    @Value("${admin.init.password:}")
    private String adminInitPassword;

    /** 加密保存敏感配置（如日志存储密码）的密钥，修改后已保存的密码需要重新填写 */
    @Value("${security.secret-key:}")
    private String secretKey;

    /** 数据库备份文件目录，运行账号需要可写 */
    @Value("${backup.dir:backups}")
    private String backupDir;

    /** pg_dump 可执行文件，不在 PATH 里时写完整路径 */
    @Value("${backup.pg-dump:pg_dump}")
    private String backupPgDump;

    /** 运维中心：app.sh、host_init.sh 所在目录（相对于工作目录，即程序目录下的 ops-scripts/） */
    @Value("${ops.script.dir:ops-scripts}")
    private String opsScriptDir;

    /** 运维中心：上传的程序包暂存目录 */
    @Value("${ops.upload.dir:ops-upload}")
    private String opsUploadDir;

    /** 运维中心：发布时程序目录 backup/ 下保留的旧版本数 */
    @Value("${ops.backup.keep:3}")
    private int opsBackupKeep;

    /** 运维中心：写给 Prometheus 的主机列表目录（file_sd，node.json、process.json），为空时不写 */
    @Value("${ops.prometheus.targets-dir:}")
    private String opsPrometheusTargetsDir;

    /** 运维中心：业务服务器上 node_exporter、process-exporter 的监听端口；「初始化」按它配置，主机列表按它写给 Prometheus */
    @Value("${ops.exporter.node-port:9100}")
    private int opsNodeExporterPort;

    @Value("${ops.exporter.process-port:9256}")
    private int opsProcessExporterPort;
}
