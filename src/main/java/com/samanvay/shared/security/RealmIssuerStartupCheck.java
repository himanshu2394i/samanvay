package com.samanvay.shared.security;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fail-fast startup check: outside the dev/demo profiles the staff realm issuer
 * (and the citizen one, if configured) must be set explicitly and use https. There are no issuer defaults in
 * {@code application.yml}, so a production boot that forgot
 * {@code SAMANVAY_STAFF_ISSUER_URI}/{@code SAMANVAY_CITIZEN_ISSUER_URI} (or
 * pointed them at http) stops here instead of trusting a laptop Keycloak.
 */
@Component
class RealmIssuerStartupCheck implements InitializingBean {

    private final SecurityRealmsProperties realms;
    private final Environment env;

    RealmIssuerStartupCheck(SecurityRealmsProperties realms, Environment env) {
        this.realms = realms;
        this.env = env;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> problems = problems(realms, DevProfiles.active(env));
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Refusing to start: " + String.join("; ", problems));
        }
    }

    static List<String> problems(SecurityRealmsProperties realms, boolean devOrDemo) {
        List<String> out = new ArrayList<>();
        check(out, "staff", realms == null ? null : realms.staff(), devOrDemo);
        // Samanvay has no citizen sign in, so the citizen realm is optional; one that IS configured is held to the same rules.
        if (realms != null && realms.citizen() != null && realms.citizen().issuerUri() != null && !realms.citizen().issuerUri().isBlank()) {
            check(out, "citizen", realms.citizen(), devOrDemo);
        }
        return out;
    }

    private static void check(List<String> out, String name, SecurityRealmsProperties.Realm realm, boolean devOrDemo) {
        String issuer = realm == null ? null : realm.issuerUri();
        String key = "samanvay.security." + name + ".issuer-uri";
        if (issuer == null || issuer.isBlank()) {
            out.add(key + " is not set");
            return;
        }
        if (devOrDemo) {
            return;
        }
        URI uri;
        try {
            uri = URI.create(issuer);
        } catch (IllegalArgumentException e) {
            out.add(key + " is not a URI");
            return;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            out.add(key + " must be an https URL outside the dev/demo profiles (got " + issuer + ")");
        }
    }
}
