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
 * Builds the redirect that sends a citizen to a department's own login. The return address must lie under an
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
        return start(citizenId, departmentCode, returnTo, allowedReturnPrefixes.stream().anyMatch(prefix -> isUnder(returnTo, prefix)));
    }

    /**
     * Same scheme, host and port as the allow-listed prefix, and a path equal to the prefix's path or below it on a {@code /}
     * boundary (so {@code /shared} allows {@code /shared/cb} but not {@code /shared-evil}). Dot segments and encoded slashes or dots
     * are refused outright; a string prefix match is never enough.
     */
    static boolean isUnder(String returnTo, String prefix) {
        try {
            if (returnTo == null || returnTo.isBlank() || returnTo.contains("\\")) {
                return false;
            }
            URI r = URI.create(returnTo.trim());
            URI p = URI.create(prefix.trim());
            if (r.getScheme() == null || r.getHost() == null || p.getScheme() == null || p.getHost() == null || r.getRawUserInfo() != null) {
                return false;
            }
            if (!r.getScheme().equalsIgnoreCase(p.getScheme()) || !r.getHost().equalsIgnoreCase(p.getHost()) || port(r) != port(p)) {
                return false;
            }
            String path = r.getRawPath() == null ? "" : r.getRawPath();
            String lower = path.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("%2e") || lower.contains("%2f") || lower.contains("%5c") || !path.equals(r.normalize().getRawPath() == null ? "" : r.normalize().getRawPath())) {
                return false;
            }
            String base = p.getRawPath() == null ? "" : p.getRawPath();
            while (base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }
            return path.equals(base) || path.startsWith(base + "/");
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static int port(URI u) {
        return u.getPort() >= 0 ? u.getPort() : "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
    }

    @Override
    @Transactional
    public String startLoginFor(String requesterDepartment, UUID citizenId, String departmentCode, String returnTo) {
        String ownOrigin = departments.identity(requesterDepartment).map(i -> origin(i.loginUrl())).orElse(null);
        String asked = returnTo == null ? null : origin(returnTo);
        return start(citizenId, departmentCode, returnTo, ownOrigin != null && ownOrigin.equals(asked) && !requesterDepartment.equals(departmentCode));
    }

    private String start(UUID citizenId, String departmentCode, String returnTo, boolean returnToAllowed) {
        if (citizenId == null) {
            throw new InvalidRequestException("citizenId is required");
        }
        DepartmentIdentity identity = departments.identity(departmentCode)
                .orElseThrow(() -> new InvalidRequestException("This department does not offer a login to link with"));
        requireHttp(identity.loginUrl());
        if (returnTo == null || returnTo.isBlank() || !returnToAllowed) {
            throw new InvalidRequestException("returnTo is not an allowed return address");
        }
        var login = states.issue(citizenId, departmentCode);
        String sep = identity.loginUrl().contains("?") ? "&" : "?";
        return identity.loginUrl() + sep + "return_to=" + enc(returnTo) + "&state=" + enc(login.state()) + "&nonce=" + enc(login.nonce());
    }

    /** scheme://host[:port], lower case; null if the address has none. */
    private static String origin(String url) {
        try {
            URI u = URI.create(url);
            return u.getScheme() == null || u.getHost() == null ? null
                    : u.getScheme().toLowerCase() + "://" + u.getHost().toLowerCase() + (u.getPort() < 0 ? "" : ":" + u.getPort());
        } catch (RuntimeException e) {
            return null;
        }
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
