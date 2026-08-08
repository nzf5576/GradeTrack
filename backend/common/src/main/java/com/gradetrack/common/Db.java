package com.gradetrack.common;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;

/**
 * Lazily-initialized connection pool, reused across warm Lambda invocations.
 * Pool is kept small since RDS free-tier instances (db.t3/t4g.micro) cap
 * max_connections low and concurrent Lambda invocations in dev are few.
 */
public final class Db {

    private static volatile DataSource dataSource;

    private Db() {
    }

    public static DataSource dataSource() {
        DataSource result = dataSource;
        if (result == null) {
            synchronized (Db.class) {
                result = dataSource;
                if (result == null) {
                    dataSource = result = buildDataSource();
                }
            }
        }
        return result;
    }

    private static DataSource buildDataSource() {
        String host = requireEnv("DB_HOST");
        String port = System.getenv().getOrDefault("DB_PORT", "5432");
        String name = requireEnv("DB_NAME");
        String user = requireEnv("DB_USER");
        String password = requireEnv("DB_PASSWORD");

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:postgresql://" + host + ":" + port + "/" + name + "?sslmode=require");
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(5000);
        config.setIdleTimeout(30000);
        config.setInitializationFailTimeout(-1);
        return new HikariDataSource(config);
    }

    private static String requireEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: " + key);
        }
        return value;
    }
}
