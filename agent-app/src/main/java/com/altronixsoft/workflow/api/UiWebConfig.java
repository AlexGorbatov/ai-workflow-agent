package com.altronixsoft.workflow.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The operator console (module operator-ui) is a static export under {@code /ui/}; Keycloak sends the user back
 * there after login. Each page is a folder with an index.html, which plain static serving does not resolve.
 */
@Configuration(proxyBeanMethods = false)
class UiWebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/ui", "/ui/");
        registry.addViewController("/ui/").setViewName("forward:/ui/index.html");
        registry.addRedirectViewController("/ui/instance", "/ui/instance/");
        registry.addViewController("/ui/instance/").setViewName("forward:/ui/instance/index.html");
    }
}
