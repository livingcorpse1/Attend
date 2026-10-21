package com.veriqra.app;

import com.veriqra.app.config.AppProperties;
import com.veriqra.app.repository.UserRepository;
import com.veriqra.app.security.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.security.SecureRandom;

/**
 * Creates the first administrator account.
 *
 * <p>An institution always needs one administrator. The account is created on the first
 * start only. The password comes from {@code BOOTSTRAP_ADMIN_PASSWORD}; when that
 * variable is not set a strong password is generated and written to the log, so a
 * deployment never starts with a well known credential.
 */
@Configuration
public class SeedData {

    private static final Logger log = LoggerFactory.getLogger(SeedData.class);

    private static final String ADMIN_EMAIL = "admin@veriqra.local";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties properties;
    private final SecureRandom random = new SecureRandom();

    public SeedData(UserRepository users, PasswordEncoder passwordEncoder, AppProperties properties) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Bean
    public ApplicationRunner seedAdministrator() {
        return (ApplicationArguments args) -> {
            String configured = properties.getBootstrapAdminPassword();
            boolean supplied = configured != null && !configured.isBlank();

            var existing = users.listByRole(Role.ADMIN).stream()
                    .filter(user -> user.active())
                    .findFirst();

            if (existing.isPresent()) {
                // A configured password is the password of the administrator. Keeping the
                // account in step with it means a deployment never keeps a password that
                // only exists in a log file.
                if (supplied) {
                    users.updatePassword(existing.get().id(), passwordEncoder.encode(configured));
                    log.info("Applied the configured password to the administrator account {}",
                            existing.get().email());
                }
                return;
            }

            String password = supplied ? configured : BootstrapPassword.generate(random);
            users.create(ADMIN_EMAIL, passwordEncoder.encode(password), "Institution Administrator",
                    Role.ADMIN, null);
            if (supplied) {
                log.info("Created the administrator account {} with the configured password", ADMIN_EMAIL);
            } else {
                log.warn("Created the administrator account {} with the generated password: {}",
                        ADMIN_EMAIL, password);
                log.warn("Store that password securely and change it after the first sign in.");
            }
        };
    }
}
