package com.veriqra.app.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Opens the database connections before the first request arrives.
 *
 * <p>The first query of a cold application has to open a connection, and with a remote
 * database that can take a second or more. Doing it while the application starts means
 * the first person to open the dashboard does not pay for it.
 */
@Configuration
public class StartupWarmup {

    private static final Logger log = LoggerFactory.getLogger(StartupWarmup.class);

    @Bean
    public ApplicationRunner warmUpDatabase(JdbcTemplate db) {
        return (ApplicationArguments args) -> {
            long started = System.nanoTime();
            try {
                db.queryForObject("select 1", Integer.class);
                log.info("Database connection ready after {} ms",
                        (System.nanoTime() - started) / 1_000_000);
            } catch (RuntimeException ex) {
                log.warn("The database could not be reached at start up: {}", ex.getMessage());
            }
            if (db.getDataSource() instanceof com.zaxxer.hikari.HikariDataSource hikari) {
                var pool = hikari.getHikariPoolMXBean();
                log.info("Pool maximum {}, active {}, idle {}", hikari.getMaximumPoolSize(),
                        pool == null ? 0 : pool.getActiveConnections(), pool == null ? 0 : pool.getIdleConnections());
            }
        };
    }
}
