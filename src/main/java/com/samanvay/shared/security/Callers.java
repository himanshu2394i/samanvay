package com.samanvay.shared.security;

import java.util.Optional;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Static access to the current {@link Caller}. Usable from any module (shared
 * is an open module) without importing another module's internals.
 *
 * <p>Only request threads carry a security context. Code that fans out onto
 * other threads (orchestration's virtual-thread fetches) must capture the
 * {@link Caller#principal()} first and pass it along explicitly.
 */
public final class Callers {

    private Callers() {}

    public static Optional<Caller> current() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof SamanvayAuthentication sa && sa.isAuthenticated()) {
            return Optional.of(sa.caller());
        }
        return Optional.empty();
    }

    public static Caller require() {
        return current().orElseThrow(() -> new AuthenticationCredentialsNotFoundException("no authenticated caller"));
    }
}
