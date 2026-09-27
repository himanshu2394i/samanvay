package com.samanvay.shared.security;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public sign-in configuration for the static pages: which issuer and browser
 * client each realm uses, and whether the dev sign-in tools are on. The pages
 * carry no IdP address of their own; the server decides, per profile.
 */
@RestController
class AuthConfigController {

    private final SecurityRealmsProperties realms;
    private final Environment env;

    AuthConfigController(SecurityRealmsProperties realms, Environment env) {
        this.realms = realms;
        this.env = env;
    }

    @GetMapping("/ui/auth-config")
    ResponseEntity<Map<String, Object>> config() {
        Map<String, Object> realmMap = new LinkedHashMap<>();
        realmMap.put("staff", realm(realms.staff()));
        realmMap.put("citizen", realm(realms.citizen()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("devSignIn", DevProfiles.active(env));
        body.put("realms", realmMap);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static Map<String, String> realm(SecurityRealmsProperties.Realm realm) {
        return Map.of("issuer", realm.issuerUri(), "clientId", realm.uiClientId() == null ? "" : realm.uiClientId());
    }
}
