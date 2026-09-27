package com.samanvay.shared.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

/** Writes 401/403 bodies in the same RFC 7807 shape as {@code shared.ApiExceptionHandler}. */
final class ProblemWriter {

    static final String REASON_ATTRIBUTE = ApiAccessRefused.REASON_ATTRIBUTE;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ProblemWriter() {}

    static void write(
            HttpServletRequest request,
            HttpServletResponse response,
            int status,
            String title,
            String problemType,
            String reason,
            String detail)
            throws IOException {
        request.setAttribute(REASON_ATTRIBUTE, reason);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "https://samanvay.dev/problems/" + problemType);
        body.put("title", title);
        body.put("status", status);
        body.put("detail", detail);
        body.put("instance", request.getRequestURI());
        body.put("reason", reason);
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(JSON.writeValueAsString(body));
    }
}
