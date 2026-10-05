package com.altronixsoft.workflow.crm;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FollowUpProperties.class)
class CrmConfig {}
