package com.samanvay.shared;

import java.net.URI;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Checks a path a department's manifest supplied before Samanvay joins it to the department's registered origin
 * ({@code scheme://host[:port]}). A path that does not start with a single '/' can change the host once joined:
 * {@code ".evil.com/x"} gives {@code https://dept.gov.evil.com/x}, {@code "@169.254.169.254/"} puts the origin in the userinfo,
 * {@code "//evil.com"} is a protocol-relative URL. The manifest planner refuses such a path (so the admin sees why) and the
 * adapters re-check at call time, so a row written some other way cannot retarget a call either.
 */
public final class EndpointPath {

    /** Placeholders ({@code {key}}) are allowed: the adapter fills them from the inputs, URL-encoded. */
    private static final Pattern PATH = Pattern.compile("^/[A-Za-z0-9._~!$&'()*+,;=:@%/{}-]*$");

    private static final Pattern QUERY = Pattern.compile("^[A-Za-z0-9._~!$&'()*+,;=:@%/?{}-]*$");

    private EndpointPath() {}

    /**
     * @param allowQuery false for what a manifest supplies (the planner builds any query itself); true at call time, where the
     *     endpoint may already carry the resolve step's fixed query
     * @return what is wrong with {@code path}, empty when it is safe to join to an origin
     */
    public static Optional<String> problem(String path, boolean allowQuery) {
        if (path == null || path.isBlank()) {
            return Optional.of("is empty");
        }
        if (!path.startsWith("/")) {
            return Optional.of("must start with '/' (got \"" + shorten(path) + "\")");
        }
        if (path.startsWith("//")) {
            return Optional.of("must not start with '//'");
        }
        String pathPart = path;
        int q = path.indexOf('?');
        if (q >= 0) {
            if (!allowQuery) {
                return Optional.of("must not carry a query or fragment");
            }
            pathPart = path.substring(0, q);
            if (!QUERY.matcher(path.substring(q + 1)).matches()) {
                return Optional.of("has a query with characters that are not allowed");
            }
        }
        if (!PATH.matcher(pathPart).matches()) {
            return Optional.of("has characters that are not allowed in a path (no '#', backslash, spaces or control characters)");
        }
        for (String segment : pathPart.split("/")) {
            String s = segment.toLowerCase(java.util.Locale.ROOT).replace("%2e", ".");
            if (s.equals("..") || s.equals(".")) {
                return Optional.of("must not contain '.' or '..' segments");
            }
        }
        return Optional.empty();
    }

    /** True when {@code target} is on the origin {@code scheme://host[:port]}: same scheme, same host (any case), same port. */
    public static boolean sameOrigin(URI target, String origin) {
        URI o = URI.create(origin);
        return target.getScheme() != null && o.getScheme() != null && target.getScheme().equalsIgnoreCase(o.getScheme())
                && target.getHost() != null && o.getHost() != null && target.getHost().equalsIgnoreCase(o.getHost())
                && target.getPort() == o.getPort() && target.getUserInfo() == null;
    }

    private static String shorten(String s) {
        return s.length() > 40 ? s.substring(0, 40) + "..." : s;
    }
}
