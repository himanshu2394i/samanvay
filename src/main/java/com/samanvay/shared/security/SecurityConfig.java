package com.samanvay.shared.security;

import static com.samanvay.shared.security.SamanvayRoles.ADMIN;
import static com.samanvay.shared.security.SamanvayRoles.CITIZEN;
import static com.samanvay.shared.security.SamanvayRoles.DEPARTMENT;
import static com.samanvay.shared.security.SamanvayRoles.OFFICER;
import static com.samanvay.shared.security.SamanvayRoles.REVIEWER;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.JwtIssuerAuthenticationManagerResolver;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * The API boundary (HLD §3.1): OAuth2 resource server accepting JWTs from
 * exactly two Keycloak realms (staff, citizen), stateless, with server-side
 * per-route role rules. Static pages stay public; every {@code /api/**} route
 * needs a bearer token; an {@code /api/**} route not listed here is denied.
 *
 * <p>Keep this table and {@code ApiAccessMatrixIT}'s expectations in step -
 * the IT enumerates the live route list, so a new controller method that is
 * not in the IT's table fails the build.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(SecurityRealmsProperties.class)
class SecurityConfig {

    static final String[] PEOPLE = {CITIZEN, OFFICER, REVIEWER, ADMIN};

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, JwtIssuerAuthenticationManagerResolver resolver)
            throws Exception {
        http.csrf(csrf -> csrf.disable()) // bearer tokens only, no cookies/sessions
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable())
                .requestCache(c -> c.disable())
                .oauth2ResourceServer(o -> o.authenticationManagerResolver(resolver)
                        .authenticationEntryPoint(entryPoint())
                        .accessDeniedHandler(accessDeniedHandler()))
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint()).accessDeniedHandler(accessDeniedHandler()))
                .addFilterAfter(new ApiAccessAuditFilter.CaptureCallerFilter(), BearerTokenAuthenticationFilter.class)
                .authorizeHttpRequests(a -> a
                        // catalog: reads for everyone signed in, operational detail for staff, writes admin-only
                        .requestMatchers(GET, "/api/catalog/departments", "/api/catalog/journeys", "/api/catalog/journeys/*")
                                .hasAnyRole(CITIZEN, OFFICER, REVIEWER, ADMIN, DEPARTMENT)
                        .requestMatchers(GET, "/api/catalog/connectors", "/api/catalog/schemas", "/api/catalog/data-sources").hasAnyRole(OFFICER, ADMIN)
                        .requestMatchers(POST, "/api/catalog/**").hasRole(ADMIN)
                        // identity: no-auto-link layer 1 of 3 (edge) - only reviewers confirm/reject
                        .requestMatchers(POST, "/api/identity/candidates/*/confirm", "/api/identity/candidates/*/reject")
                                .hasRole(REVIEWER)
                        .requestMatchers(GET, "/api/identity/review-queue").hasRole(REVIEWER)
                        .requestMatchers(POST, "/api/identity/citizens").hasAnyRole(CITIZEN, OFFICER)
                        // officer "Find a citizen" search: staff only - must sit before the citizen-readable /citizens/**
                        .requestMatchers(GET, "/api/identity/citizens/search").hasRole(OFFICER)
                        .requestMatchers(GET, "/api/identity/citizens/**").hasAnyRole(CITIZEN, OFFICER, REVIEWER)
                        .requestMatchers(GET, "/api/identity/proof-providers").hasAnyRole(CITIZEN, OFFICER)
                        .requestMatchers(POST, "/api/identity/links").hasRole(CITIZEN)
                        // consent: only the citizen grants/revokes (own record, checked in controller)
                        .requestMatchers(POST, "/api/consent/requests").hasAnyRole(CITIZEN, OFFICER, DEPARTMENT)
                        .requestMatchers(POST, "/api/consent/requests/*/grant").hasRole(CITIZEN)
                        .requestMatchers(POST, "/api/consent/*/revoke", "/api/consent/me/*/revoke").hasRole(CITIZEN)
                        .requestMatchers(GET, "/api/consent/citizens/*").hasAnyRole(CITIZEN, OFFICER)
                        // orchestration: officers own the exception queue and retries
                        .requestMatchers(POST, "/api/journeys/*/start").hasAnyRole(CITIZEN, OFFICER, DEPARTMENT)
                        .requestMatchers(POST, "/api/journeys/instances/*/retry").hasRole(OFFICER)
                        // officer approval step: VERIFIED -> APPROVED (fires the disbursement)
                        .requestMatchers(POST, "/api/journeys/instances/*/approve").hasRole(OFFICER)
                        // officer rejection step: non-terminal -> REJECTED (with a reason; nothing disburses)
                        .requestMatchers(POST, "/api/journeys/instances/*/reject").hasRole(OFFICER)
                        .requestMatchers(GET, "/api/journeys/exceptions", "/api/journeys/instances/*").hasRole(OFFICER)
                        // officer bank-account review (no holder name is ever returned)
                        .requestMatchers(GET, "/api/officer/bank-reviews").hasRole(OFFICER)
                        .requestMatchers(POST, "/api/officer/bank-reviews/*/passbook",
                                "/api/officer/bank-reviews/*/request-document",
                                "/api/officer/bank-reviews/*/approve",
                                "/api/officer/bank-reviews/*/reject").hasRole(OFFICER)
                        // tracking
                        .requestMatchers(GET, "/api/applications", "/api/applications/**").hasAnyRole(CITIZEN, OFFICER)
                        // connector
                        .requestMatchers(GET, "/api/connector/issued-documents").hasAnyRole(CITIZEN, OFFICER)
                        .requestMatchers("/api/connector/chaos/**").hasAnyRole(OFFICER, ADMIN) // @Profile("demo") only
                        // audit
                        .requestMatchers(POST, "/api/audit/demo/**").hasRole(ADMIN) // @Profile("demo") only
                        .requestMatchers(GET, "/api/audit/**").hasAnyRole(OFFICER, ADMIN)
                        // ops dashboards (connector health, SLA, consent/access, exception queue): staff only.
                        // The only route that exposes metrics; no actuator/Prometheus endpoint is served.
                        .requestMatchers(GET, "/api/ops/**").hasAnyRole(OFFICER, ADMIN)
                        // fail closed for anything new under /api
                        .requestMatchers("/api", "/api/**").denyAll()
                        // static pages, citizen portal skins, error page
                        .anyRequest().permitAll());
        return http.build();
    }

    @Bean
    JwtIssuerAuthenticationManagerResolver jwtIssuerResolver(
            SecurityRealmsProperties realms,
            RealmIssuerStartupCheck issuersChecked,
            org.springframework.beans.factory.ObjectProvider<AdditionalAuthManagers> extra) {
        Map<String, AuthenticationManager> managers = new LinkedHashMap<>();
        register(managers, realms.audience(), realms.staff(), KeycloakJwtConverter.RealmKind.STAFF);
        register(managers, realms.audience(), realms.citizen(), KeycloakJwtConverter.RealmKind.CITIZEN);
        // Demo profile only: a demo-signin issuer the API also trusts (no such bean in prod).
        extra.ifAvailable(a -> managers.putAll(a.byIssuer()));
        // Unknown issuer -> null manager -> InvalidBearerTokenException -> 401.
        return new JwtIssuerAuthenticationManagerResolver(managers::get);
    }

    @Bean
    FilterRegistrationBean<ApiAccessAuditFilter> apiAccessAuditFilter(
            ApplicationEventPublisher events,
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mapping,
            MeterRegistry meters) {
        var reg = new FilterRegistrationBean<>(new ApiAccessAuditFilter(events, RouteTemplates.from(mapping), meters));
        // Outside Spring Security's chain (DEFAULT_FILTER_ORDER = -100), so it
        // sees the final status of every refused /api request.
        reg.setOrder(-101);
        reg.addUrlPatterns("/api/*");
        return reg;
    }

    private static void register(
            Map<String, AuthenticationManager> managers,
            String audience,
            SecurityRealmsProperties.Realm realm,
            KeycloakJwtConverter.RealmKind kind) {
        if (realm == null || realm.issuerUri() == null || realm.issuerUri().isBlank()) {
            throw new IllegalStateException("samanvay.security." + kind.name().toLowerCase() + ".issuer-uri must be set");
        }
        if (realm.allowedClients().isEmpty()) {
            throw new IllegalStateException(
                    "samanvay.security." + kind.name().toLowerCase() + ".allowed-clients must list the realm's clients");
        }
        JwtAuthenticationProvider provider = new JwtAuthenticationProvider(decoder(realm, audience));
        provider.setJwtAuthenticationConverter(new KeycloakJwtConverter(kind));
        managers.put(realm.issuerUri(), provider::authenticate);
    }

    static JwtDecoder decoder(SecurityRealmsProperties.Realm realm, String audience) {
        String issuer = realm.issuerUri();
        // signature is checked by the decoder; then issuer + exp/nbf, then aud/azp/typ
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new KeycloakTokenValidator(audience, realm.allowedClients()));
        if (realm.publicKeyLocation() != null) {
            NimbusJwtDecoder d = NimbusJwtDecoder.withPublicKey(readPublicKey(realm)).build();
            d.setJwtValidator(validator);
            return d;
        }
        if (realm.jwkSetUri() != null && !realm.jwkSetUri().isBlank()) {
            NimbusJwtDecoder d = NimbusJwtDecoder.withJwkSetUri(realm.jwkSetUri()).build();
            d.setJwtValidator(validator);
            return d;
        }
        // Lazy OIDC discovery: the app boots even if Keycloak is not up yet.
        return new SupplierJwtDecoder(() -> {
            NimbusJwtDecoder d = JwtDecoders.fromIssuerLocation(issuer);
            d.setJwtValidator(validator);
            return d;
        });
    }

    private static RSAPublicKey readPublicKey(SecurityRealmsProperties.Realm realm) {
        try (InputStream in = realm.publicKeyLocation().getInputStream()) {
            String pem = new String(in.readAllBytes(), StandardCharsets.US_ASCII)
                    .replaceAll("-----(BEGIN|END) PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));
        } catch (IOException | java.security.GeneralSecurityException e) {
            throw new IllegalStateException("cannot read public key for issuer " + realm.issuerUri(), e);
        }
    }

    private static AuthenticationEntryPoint entryPoint() {
        BearerTokenAuthenticationEntryPoint header = new BearerTokenAuthenticationEntryPoint();
        return (request, response, ex) -> {
            header.commence(request, response, ex); // WWW-Authenticate: Bearer ...
            ProblemWriter.write(
                    request,
                    response,
                    401,
                    "Unauthorized",
                    "security/unauthenticated",
                    "UNAUTHENTICATED",
                    "A valid bearer token from the staff or citizen realm is required");
        };
    }

    private static AccessDeniedHandler accessDeniedHandler() {
        return (request, response, ex) -> ProblemWriter.write(
                request,
                response,
                403,
                "Forbidden",
                "security/forbidden",
                "FORBIDDEN",
                ex.getMessage() == null || ex.getMessage().isBlank() ? "Access denied" : ex.getMessage());
    }
}
