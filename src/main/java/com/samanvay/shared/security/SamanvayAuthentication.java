package com.samanvay.shared.security;

import java.util.Collection;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** A validated JWT plus the {@link Caller} derived from it. */
public final class SamanvayAuthentication extends JwtAuthenticationToken {

    private final Caller caller;

    SamanvayAuthentication(Jwt jwt, Collection<? extends GrantedAuthority> authorities, Caller caller) {
        super(jwt, authorities, caller.subject());
        this.caller = caller;
    }

    public Caller caller() {
        return caller;
    }

    @Override
    public boolean equals(Object obj) {
        return super.equals(obj);
    }

    @Override
    public int hashCode() {
        return super.hashCode();
    }
}
