package com.samanvay.notifications.internal.channel;

import com.samanvay.notifications.api.TemplateNotFoundException;
import com.samanvay.notifications.api.UnresolvedTemplatePlaceholderException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class TemplateRenderer {

    public String render(String templateRef, Map<String, String> variables) {
        String template = load(templateRef);
        for (var e : variables.entrySet()) {
            String value = e.getValue() == null
                    ? ""
                    : e.getValue().replace("&", "&amp;").replace("<", "&lt;").replace("{", "&#123;");
            template = template.replace("{{" + e.getKey() + "}}", value);
        }
        if (template.matches("(?s).*\\{\\{[A-Za-z0-9_]+\\}\\}.*")) {
            throw new UnresolvedTemplatePlaceholderException(templateRef);
        }
        return template;
    }

    String load(String templateRef) {
        String path = "templates/notifications/" + templateRef + ".txt";
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new TemplateNotFoundException(templateRef);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new TemplateNotFoundException(templateRef);
        }
    }
}
