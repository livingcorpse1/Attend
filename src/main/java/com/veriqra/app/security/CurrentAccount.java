package com.veriqra.app.security;

import com.veriqra.app.domain.AppUser;
import com.veriqra.app.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Resolves the account behind the current request.
 *
 * <p>Controllers ask for the identifier of the signed in account through this component
 * instead of trusting anything that arrived in the request body.
 */
@Component
public class CurrentAccount {

    private final UserRepository users;

    public CurrentAccount(UserRepository users) {
        this.users = users;
    }

    /** The account id of the current request. */
    public long id(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getName() == null) {
            throw ApiException.unauthorized("UNAUTHENTICATED", "Sign in to continue.");
        }
        AppUser user = users.findByEmail(authentication.getName())
                .orElseThrow(() -> ApiException.unauthorized("ACCOUNT_MISSING",
                        "The signed in account no longer exists."));
        return user.id();
    }

    /** The full account of the current request. */
    public AppUser user(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getName() == null) {
            throw ApiException.unauthorized("UNAUTHENTICATED", "Sign in to continue.");
        }
        return users.findByEmail(authentication.getName())
                .orElseThrow(() -> ApiException.unauthorized("ACCOUNT_MISSING",
                        "The signed in account no longer exists."));
    }
}
