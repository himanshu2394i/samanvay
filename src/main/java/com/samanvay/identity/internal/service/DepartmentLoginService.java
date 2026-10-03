package com.samanvay.identity.internal.service;

import com.samanvay.catalog.api.DepartmentCatalog;
import com.samanvay.catalog.api.DepartmentIdentity;
import com.samanvay.identity.api.DepartmentLogin;
import com.samanvay.identity.internal.proof.DepartmentLoginStates;
import com.samanvay.shared.InvalidRequestException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the redirect that sends a citizen to a department's own login. The return address must start with an
 * allow-listed Samanvay prefix ({@code samanvay.identity.department-assertion.allowed-return-prefixes}); with none
 * configured every address is refused (fail closed). Nothing is issued when a request is refused.
 */
@Service
class DepartmentLoginService implements DepartmentLogin {

    private final DepartmentCatalog departments;
    private final DepartmentLoginStates states;
    private final List<String> allowedReturnPrefixes;

    @Autowired
    DepartmentLoginService(
            DepartmentCatalog departments,
            DepartmentLoginStates states,
            @Value("${samanvay.identity.department-assertion.allowed-return-prefixes:}") List<String> allowedReturnPrefixes) {
        this.departments = departments;
        this.states = states;
        this.allowedReturnPrefixes = allowedReturnPrefixes.stream().map(String::trim).filter(p -> !p.isEmpty()).toList();
    }

    @Override
    @Transactional
    public String startLogin(UUID citizenId, String departmentCode, String returnTo) {
        if (citizenId == null) {
            throw new InvalidRequestException("citizenId is required");
        }
        DepartmentIdentity identity = departments.identity(departmentCode)
                .orElseThrow(() -> new InvalidRequestException("This department does not offer a login to link with"));
        requireHttp(identity.loginUrl());
        if (returnTo == null || returnTo.isBlank() || allowedReturnPrefixes.stream().noneMatch(returnTo::startsWith)) {
            throw new InvalidRequestException("returnTo is not an allowed return address");
        }
        var login = states.issue(citizenId, departmentCode);
        String sep = identity.loginUrl().contains("?") ? "&" : "?";
        return identity.loginUrl() + sep + "return_to=" + enc(returnTo) + "&state=" + enc(login.state()) + "&nonce=" + enc(login.nonce());
    }

    private static void requireHttp(String url) {
        String scheme;
        try {
            scheme = URI.create(url == null ? "" : url).getScheme();
        } catch (RuntimeException e) {
            scheme = null;
        }
        if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))) {
            throw new InvalidRequestException("This department's login address is not usable");
        }
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
