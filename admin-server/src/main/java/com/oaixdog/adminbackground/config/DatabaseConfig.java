package com.oaixdog.adminbackground.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class DatabaseConfig {

    private final ApplicationProperties properties;

    @Bean(destroyMethod = "close")
    public HikariDataSource dataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(properties.getPostgresqlUrl());
        config.setUsername(properties.getPostgresqlUsername());
        config.setPassword(properties.getPostgresqlPassword());
        config.setDriverClassName("org.postgresql.Driver");
        config.setMaximumPoolSize(properties.getPostgresqlMaximumPoolSize());
        config.setMinimumIdle(properties.getPostgresqlMinimumIdle());
        config.setConnectionTimeout(properties.getPostgresqlConnectionTimeout());
        config.setMaxLifetime(600_000);
        config.setIdleTimeout(300_000);
        config.setKeepaliveTime(30_000);
        config.setValidationTimeout(1_000);
        config.addDataSourceProperty("tcpKeepAlive", "true");
        return new HikariDataSource(config);
    }
}
