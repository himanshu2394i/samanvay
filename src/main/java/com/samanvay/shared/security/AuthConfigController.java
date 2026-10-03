package com.samanvay.shared.security;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public sign-in configuration for the static pages: which issuer and browser
 * client each realm uses. The pages carry no IdP address of their own; the
 * server decides, per profile. There is no demo or dev sign-in switch.
 */
@RestController
class AuthConfigController {

    private final SecurityRealmsProperties realms;

    AuthConfigController(SecurityRealmsProperties realms) {
        this.realms = realms;
    }

    @GetMapping("/ui/auth-config")
    ResponseEntity<Map<String, Object>> config() {
        Map<String, Object> realmMap = new LinkedHashMap<>();
        realmMap.put("staff", realm(realms.staff()));
        realmMap.put("citizen", realm(realms.citizen()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("realms", realmMap);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static Map<String, String> realm(SecurityRealmsProperties.Realm realm) {
        return Map.of("issuer", realm.issuerUri(), "clientId", realm.uiClientId() == null ? "" : realm.uiClientId());
    }
}
