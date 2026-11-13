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

    /** 系统里还没有任何账号时，启动时创建的初始超级管理员账号；密码随机生成并打印到日志 */
    @Value("${admin.init.account:admin}")
    private String adminInitAccount;

    /** 加密保存敏感配置（如日志存储密码）的密钥，修改后已保存的密码需要重新填写 */
    @Value("${security.secret-key:}")
    private String secretKey;
}
