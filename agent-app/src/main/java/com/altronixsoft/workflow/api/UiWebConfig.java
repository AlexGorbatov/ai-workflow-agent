package com.altronixsoft.workflow.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** The UI lives at {@code /ui/} (also where Keycloak sends the user back after login). */
@Configuration(proxyBeanMethods = false)
class UiWebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/ui", "/ui/");
        registry.addViewController("/ui/").setViewName("forward:/ui/index.html");
    }
}
