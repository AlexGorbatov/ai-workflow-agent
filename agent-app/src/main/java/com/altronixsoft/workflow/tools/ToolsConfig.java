package com.altronixsoft.workflow.tools;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({MailProperties.class, ToolsProperties.class})
class ToolsConfig {}
