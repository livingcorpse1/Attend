package com.veriqra.app.web;

import com.veriqra.app.DemoData;
import com.veriqra.app.config.AppProperties;
import com.veriqra.app.domain.dto.AccountDtos.AccountProfile;
import com.veriqra.app.domain.dto.AccountDtos.LoginRequest;
import com.veriqra.app.domain.dto.AccountDtos.RegisterRequest;
import com.veriqra.app.domain.dto.AccountDtos.SessionBootstrap;
import com.veriqra.app.service.auth.AuthService;
import com.veriqra.app.service.settings.SettingsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sign in, sign out and self registration.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final SettingsService settings;
    private final AppProperties properties;
    private final ObjectProvider<DemoData> demoData;
    private final SecurityContextRepository securityContextRepository;

    public AuthController(AuthService authService,
                          SettingsService settings,
                          AppProperties properties,
                          ObjectProvider<DemoData> demoData,
                          SecurityContextRepository securityContextRepository) {
        this.authService = authService;
        this.settings = settings;
        this.properties = properties;
        this.demoData = demoData;
        this.securityContextRepository = securityContextRepository;
    }

    /**
     * Bootstraps the client.
     *
     * <p>Always callable without a session. It hands out the CSRF token the SPA must echo
     * on every state changing request, tells the client who is signed in, and describes
     * the institution. The name, the location and the version of the biometric notice
     * come from the institution settings, so nothing about the college is compiled into
     * the application.
     */
    @GetMapping("/session")
    public SessionBootstrap session(Authentication authentication, CsrfToken csrfToken) {
        AccountProfile account = null;
        if (authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getPrincipal())) {
            account = authService.profileOf(authentication);
        }
        DemoData demo = demoData.getIfAvailable();
        return new SessionBootstrap(
                csrfToken.getToken(),
                account,
                institution(),
                settings.get(SettingsService.CONSENT_VERSION, ""),
                demo != null,
                demo == null ? List.of() : demo.accountHints());
    }

    /** Signs an account in. */
    @PostMapping("/login")
    public ResponseEntity<AccountProfile> login(@Valid @RequestBody LoginRequest request,
                                                 HttpServletRequest servletRequest,
                                                 HttpServletResponse servletResponse) {
        Authentication authentication = authService.authenticate(
                request.email(), request.password(), clientAddress(servletRequest));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, servletRequest, servletResponse);
        return ResponseEntity.ok(authService.profileOf(authentication));
    }

    /**
     * Creates a student account.
     *
     * <p>The account is created in the pending biometric state. The client signs the new
     * account in immediately and continues to the identity check.
     */
    @PostMapping("/register")
    public ResponseEntity<AccountProfile> register(@Valid @RequestBody RegisterRequest request,
                                                    HttpServletRequest servletRequest,
                                                    HttpServletResponse servletResponse) {
        AccountProfile profile = authService.register(request);
        Authentication authentication = authService.authenticate(
                request.email(), request.password(), clientAddress(servletRequest));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, servletRequest, servletResponse);
        return ResponseEntity.status(HttpStatus.CREATED).body(profile);
    }

    /** Signs the current account out. */
    @PostMapping("/logout")
    public Map<String, String> logout(HttpServletRequest request) {
        if (request.getSession(false) != null) {
            request.getSession(false).invalidate();
        }
        SecurityContextHolder.clearContext();
        return Map.of("status", "signed out");
    }

    /** The signed in account. */
    @GetMapping("/me")
    public AccountProfile me(Authentication authentication) {
        return authService.profileOf(authentication);
    }

    private Map<String, String> institution() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("name", settings.get(SettingsService.INSTITUTION_NAME, properties.getInstitutionName()));
        values.put("location", settings.get(SettingsService.INSTITUTION_LOCATION, properties.getInstitutionLocation()));
        return values;
    }

    /** Best effort client address, honouring a reverse proxy header when present. */
    private static String clientAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }
}
