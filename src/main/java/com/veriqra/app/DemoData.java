package com.veriqra.app;

import com.veriqra.app.repository.UserRepository;
import com.veriqra.app.security.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Creates the demonstration accounts.
 *
 * <p>Only the accounts are created. The academic structure around them, the subjects,
 * the class sections, the rooms and the weekly schedule, is not seeded: that belongs to
 * the college and is entered by an administrator.
 *
 * <p>The accounts exist so the application can be tried out on a fresh installation. They
 * can be switched off with {@code DEMO_DATA_ENABLED=false}, and they are never created
 * in the {@code prod} profile.
 */
@Configuration
@Profile("!prod")
@ConditionalOnProperty(name = "veriqra.demo-data.enabled", havingValue = "true", matchIfMissing = true)
public class DemoData {

    private static final Logger log = LoggerFactory.getLogger(DemoData.class);

    /** The accounts that are created, in the order they are listed. */
    private record DemoAccount(String email, String fullName, Role role, String studentNumber) {
    }

    private static final String DEMO_PASSWORD = "Demo!2026";

    private static final DemoAccount[] DEMO_ACCOUNTS = {
            new DemoAccount("faculty@veriqra.local", "Dr. Maya Rao", Role.FACULTY, null),
            new DemoAccount("faculty2@veriqra.local", "Prof. Anand Iyer", Role.FACULTY, null),
            new DemoAccount("student@veriqra.local", "Aarav Sharma", Role.STUDENT, "VQ24001"),
            new DemoAccount("student2@veriqra.local", "Diya Patil", Role.STUDENT, "VQ24002"),
            new DemoAccount("student3@veriqra.local", "Rohan Deshmukh", Role.STUDENT, "VQ24003")
    };

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public DemoData(UserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @Bean
    public ApplicationRunner seedDemoAccounts() {
        return (ApplicationArguments args) -> {
            int created = 0;
            int reset = 0;
            for (DemoAccount account : DEMO_ACCOUNTS) {
                var existing = users.findByEmail(account.email());
                if (existing.isEmpty()) {
                    users.create(account.email(), passwordEncoder.encode(DEMO_PASSWORD),
                            account.fullName(), account.role(), account.studentNumber());
                    created += 1;
                    continue;
                }
                // The demonstration password is published on the sign in screen, so it is
                // kept in step with the account. A password that was changed on purpose
                // is therefore reset on the next start.
                users.updatePassword(existing.get().id(), passwordEncoder.encode(DEMO_PASSWORD));
                reset += 1;
            }
            if (created > 0) {
                log.info("Created {} demonstration account(s). They can be removed or disabled from the "
                        + "administration screens.", created);
            }
            if (reset > 0) {
                log.info("Reset the password of {} demonstration account(s).", reset);
            }
        };
    }

    /**
     * The demonstration accounts, for the sign in screen to show.
     *
     * @return one line per account
     */
    public java.util.List<String> accountHints() {
        java.util.List<String> hints = new java.util.ArrayList<>();
        for (DemoAccount account : DEMO_ACCOUNTS) {
            hints.add(account.email() + " / " + DEMO_PASSWORD);
        }
        return hints;
    }
}
