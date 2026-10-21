package com.veriqra.app.web;

import com.zaxxer.hikari.HikariConfigMXBean;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The connection pool against a managed database.
 *
 * <p>The pooler in front of a managed database closes connections that have been idle
 * for a while. A pool that hands those dead connections to a request makes every query
 * fail, and the symptom is a login screen that says "something went wrong" with no
 * hint what went wrong. These tests pin the settings that stop that from happening.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DatabasePoolConfigurationTest {

    /** Below this value the pool switches the keepalive off without saying so. */
    private static final long MINIMUM_KEEPALIVE = 30_000L;

    @Autowired
    JdbcTemplate db;

    @Autowired
    Environment environment;

    @Autowired
    MockMvc mvc;

    @Test
    void theKeepaliveIsLongEnoughToStaySwitchedOn() {
        assertThat(keepaliveTime())
                .as("a keepalive below %d ms is ignored by the pool, which is exactly what "
                        + "let the dead connections through", MINIMUM_KEEPALIVE)
                .isGreaterThanOrEqualTo(MINIMUM_KEEPALIVE);
    }

    @Test
    void aConnectionIsRetiredBeforeTheServerWouldDropIt() {
        assertThat(maxLifetime())
                .as("a connection has to be retired before the server drops it")
                .isLessThan(600_000L);
        assertThat(maxLifetime())
                .as("the keepalive has to be shorter than the lifetime, otherwise the pool "
                        + "never gets to check the connection")
                .isGreaterThan(keepaliveTime());
    }

    @Test
    void thePoolWaitsLongEnoughForAReplacementConnection() {
        assertThat(connectionTimeout())
                .as("the pool needs time to open a replacement connection")
                .isGreaterThanOrEqualTo(15_000L);
    }

    @Test
    void thePoolDoesNotHoardIdleConnections() {
        assertThat(minimumIdle())
                .as("every extra idle connection is another one that can go stale")
                .isLessThanOrEqualTo(1);
        assertThat(idleTimeout())
                .as("unused connections should be dropped long before they go stale")
                .isLessThanOrEqualTo(120_000L);
    }

    @Test
    void thePoolIsRunningWithTheExpectedShape() {
        HikariConfigMXBean config = hikariConfig();
        assertThat(config.getMaximumPoolSize()).isGreaterThanOrEqualTo(1);
        assertThat(config.getPoolName()).isEqualTo("veriqra-pool");
    }

    @Test
    void theHealthEndpointReportsTheDatabase() throws Exception {
        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database.status").value("UP"))
                .andExpect(jsonPath("$.biometricAvailable").exists());
    }

    @Test
    void anUnsupportedMethodIsNotReportedAsAnInternalError() throws Exception {
        // With a session, so the request reaches the dispatcher instead of being turned
        // away by the security filter.
        MockHttpSession session = signIn();
        {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .get("/api/auth/logout").session(session))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        }
    }

    @Test
    void anUnknownAddressIsNotReportedAsAnInternalError() throws Exception {
        MockHttpSession session = signIn();
        {
            mvc.perform(get("/api/does-not-exist").session(session))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
    }

    @Test
    void anUnauthenticatedRequestIsTurnedAwayRatherThanDescribed() throws Exception {
        // The answer must not say which addresses exist.
        mvc.perform(get("/api/does-not-exist"))
                .andExpect(status().is4xxClientError());
    }

    private MockHttpSession signIn() throws Exception {
        var result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/auth/login")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"faculty@veriqra.local\",\"password\":\"Demo!2026\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    /* ------------------------------------------------------------------ helpers */

    private long keepaliveTime() {
        return environment.getProperty("spring.datasource.hikari.keepalive-time", Long.class, 0L);
    }

    private long maxLifetime() {
        return environment.getProperty("spring.datasource.hikari.max-lifetime", Long.class, 0L);
    }

    private long connectionTimeout() {
        return environment.getProperty("spring.datasource.hikari.connection-timeout", Long.class, 0L);
    }

    private long idleTimeout() {
        return environment.getProperty("spring.datasource.hikari.idle-timeout", Long.class, 0L);
    }

    private int minimumIdle() {
        return environment.getProperty("spring.datasource.hikari.minimum-idle", Integer.class, 0);
    }

    private HikariConfigMXBean hikariConfig() {
        if (db.getDataSource() instanceof HikariDataSource hikari) {
            HikariConfigMXBean config = hikari.getHikariConfigMXBean();
            assertThat(config).as("the connection pool is not running").isNotNull();
            return config;
        }
        throw new IllegalStateException("The connection pool is not a Hikari pool");
    }
}
