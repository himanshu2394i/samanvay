package in.samanvay.departments.kit;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriUtils;

/**
 * What a department's filters decide on. The raw {@code getRequestURI()} is NOT the path the container routes: Tomcat drops
 * {@code ;parameters}, decodes percent escapes and resolves dot segments before Spring sees it, so {@code /v1;x=1/income/1} reaches the
 * {@code /v1/**} handlers while a raw {@code startsWith("/v1/")} says it is not a {@code /v1} call. So:
 * <ol>
 *   <li>{@link #malformed} flags every spelling that could make the two differ (';', encoded '.', '/', ';', '\', dot segments, '//').
 *       Filters answer such a request with 400 and never look further (fail closed).</li>
 *   <li>For what is left, {@link #normalised} does what the container does, and {@link #isPublic} is an explicit allow-list:
 *       everything not on it is protected.</li>
 * </ol>
 */
public final class RequestPaths {

    private static final Pattern ENCODED_DANGER = Pattern.compile("%(2[eEfF]|5[cC]|3[bB]|00)");
    private static final Pattern PATH_PARAMS = Pattern.compile(";[^/]*");
    private static final Pattern BAD_ESCAPE = Pattern.compile("%(?![0-9a-fA-F]{2})");
    /** What can never be a public path: used for a path that cannot be decoded. */
    private static final String UNROUTABLE = "\0";

    private RequestPaths() {}

    public static boolean malformed(HttpServletRequest request) {
        return malformed(request.getRequestURI());
    }

    public static boolean malformed(String rawUri) {
        if (rawUri == null || rawUri.isEmpty() || rawUri.charAt(0) != '/') {
            return true;
        }
        if (rawUri.indexOf(';') >= 0 || rawUri.indexOf('\\') >= 0 || rawUri.indexOf('\0') >= 0 || rawUri.contains("//")) {
            return true;
        }
        if (ENCODED_DANGER.matcher(rawUri).find() || BAD_ESCAPE.matcher(rawUri).find()) {
            return true;
        }
        for (String segment : rawUri.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                return true;
            }
        }
        return false;
    }

    /** The path as the container routes it: ';parameters' dropped, escapes decoded, dot segments resolved. */
    public static String normalised(String rawUri) {
        if (rawUri == null) {
            return UNROUTABLE;
        }
        String noParams = PATH_PARAMS.matcher(rawUri).replaceAll("");
        try {
            String decoded = UriUtils.decode(noParams, StandardCharsets.UTF_8);
            String cleaned = StringUtils.cleanPath(decoded);
            return cleaned.startsWith("/") ? cleaned : UNROUTABLE;
        } catch (IllegalArgumentException e) {
            return UNROUTABLE;
        }
    }

    public static String normalised(HttpServletRequest request) {
        return normalised(request.getRequestURI());
    }

    /**
     * The paths that need no department credential. The citizen portal and login protect themselves (session cookie, password and
     * code); the manifest and the signing keys are public by design (the manifest may also ask for a discovery key); the rest of the
     * allow-list is the landing redirect, the error page, the icon and the health probe. Everything else, including every path nobody
     * has thought of yet, is protected.
     */
    public static boolean isPublic(String normalisedPath) {
        String p = normalisedPath;
        return p.equals("/") || p.equals("/error") || p.equals("/favicon.ico") || p.equals("/portal") || p.startsWith("/portal/")
                || p.startsWith("/portal-api/") || p.equals("/login") || p.startsWith("/login/") || p.startsWith("/.well-known/")
                || p.equals("/actuator/health") || p.startsWith("/actuator/health/");
    }

    /** True only for a well-formed request to a public path; a malformed one is never public. */
    public static boolean isPublic(HttpServletRequest request) {
        return !malformed(request) && isPublic(normalised(request));
    }
}
