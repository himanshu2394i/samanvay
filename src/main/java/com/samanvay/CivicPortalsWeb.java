package com.samanvay;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** GET forwards for the staff console (the React SPA). */
@Configuration
class CivicPortalsWeb implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // The React SPA (frontend/, built into static/app by the `frontend` Maven profile).
        // It uses hash routing, so every in-app route is /app/#/...; only /app/ needs a forward.
        registry.addViewController("/app").setViewName("forward:/app/index.html");
        registry.addViewController("/app/").setViewName("forward:/app/index.html");
        registry.setOrder(Ordered.HIGHEST_PRECEDENCE);
    }
}
