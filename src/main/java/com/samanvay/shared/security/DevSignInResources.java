package com.samanvay.shared.security;

import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the dev sign-in tools (paste-token bar) only under the dev/demo
 * profiles. The script lives outside {@code static/}, so any other boot
 * answers 404 for it.
 */
@RestController
@Profile({"dev", "demo"})
class DevSignInResources {

    private static final Resource SCRIPT = new ClassPathResource("dev-static/shared/auth-dev.js");

    @GetMapping(value = "/shared/auth-dev.js", produces = "text/javascript")
    ResponseEntity<Resource> script() {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/javascript")).body(SCRIPT);
    }
}
