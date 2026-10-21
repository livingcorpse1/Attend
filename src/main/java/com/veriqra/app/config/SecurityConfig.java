package com.veriqra.app.config;

import com.veriqra.app.security.Role;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Locale;

/**
 * HTTP security policy for the whole application.
 *
 * <p>Decisions worth calling out:
 * <ul>
 *   <li>Authentication is cookie and session based, so CSRF protection is enabled and
 *       the SPA reads the {@code XSRF-TOKEN} cookie and echoes it in {@code X-XSRF-TOKEN}.</li>
 *   <li>Every API route requires an authenticated session except the small public
 *       bootstrap surface (login, registration, session bootstrap, health).</li>
 *   <li>Role scoping is enforced by URL patterns here and re-checked in the service
 *       layer against the database, so a stale UI can never widen access.</li>
 *   <li>The content security policy forbids third party origins entirely: the client
 *       ships no CDN assets.</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public UserDetailsService userDetailsService(com.veriqra.app.repository.UserRepository users) {
        return email -> {
            String normalised = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
            var stored = users.findStoredByEmail(normalised);
            if (stored.isEmpty()) {
                throw new UsernameNotFoundException("No account for " + normalised);
            }
            var account = stored.get();
            UserDetails principal = User.withUsername(account.email())
                    .password(account.passwordHash())
                    .roles(account.role().name())
                    .disabled(!account.active())
                    .build();
            return principal;
        };
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   SecurityContextRepository securityContextRepository)
            throws Exception {

        CookieCsrfTokenRepository csrfTokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokenRepository.setCookiePath("/");

        CsrfTokenRequestAttributeHandler csrfRequestHandler = new CsrfTokenRequestAttributeHandler();
        csrfRequestHandler.setCsrfRequestAttributeName(null);

        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(csrfRequestHandler))
                .securityContext(context -> context
                        .securityContextRepository(securityContextRepository)
                        .requireExplicitSave(false))
                .sessionManagement(session -> session
                        .sessionFixation(fixation -> fixation.migrateSession())
                        .sessionConcurrency(concurrency -> concurrency.maximumSessions(5)))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(
                                "/",
                                "/index.html",
                                "/offline.html",
                                "/manifest.webmanifest",
                                "/sw.js",
                                "/favicon.svg",
                                "/css/**",
                                "/js/**",
                                "/vendor/**",
                                "/icons/**",
                                "/api/health",
                                "/api/auth/session",
                                "/api/auth/login",
                                "/api/auth/register").permitAll()
                        .requestMatchers("/api/faculty/**").hasRole(Role.FACULTY.name())
                        .requestMatchers("/api/student/**").hasRole(Role.STUDENT.name())
                        .requestMatchers("/api/audit-logs").hasRole(Role.FACULTY.name())
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .headers(headers -> {
                    headers.contentSecurityPolicy(csp -> csp.policyDirectives(
                            "default-src 'self'; "
                                    + "script-src 'self'; "
                                    + "style-src 'self'; "
                                    + "img-src 'self' data: blob:; "
                                    + "media-src 'self' blob:; "
                                    + "connect-src 'self'; "
                                    + "font-src 'self'; "
                                    + "worker-src 'self'; "
                                    + "manifest-src 'self'; "
                                    + "base-uri 'self'; "
                                    + "form-action 'self'; "
                                    + "frame-ancestors 'none'"));
                    headers.frameOptions(frame -> frame.deny());
                    headers.referrerPolicy(referrer -> referrer.policy(
                            org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER));
                    headers.permissionsPolicy(permissions -> permissions.policy(
                            "camera=(self), microphone=(), geolocation=(), payment=(), usb=()"));
                    headers.httpStrictTransportSecurity(hsts -> hsts
                            .includeSubDomains(true)
                            .maxAgeInSeconds(Duration.ofDays(365).toSeconds()));
                })
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .anonymous(anonymous -> anonymous.disable())
                .requestCache(AbstractHttpConfigurer::disable);

        http.addFilterAfter(new CsrfCookieEmittingFilter(), org.springframework.security.web.csrf.CsrfFilter.class);

        return http.build();
    }

    /**
     * Forces the CSRF token to be materialised on every response so the SPA always
     * has a token available before it issues its first state changing request.
     */
    static final class CsrfCookieEmittingFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (token != null) {
                token.getToken();
            }
            chain.doFilter(request, response);
        }
    }
}
