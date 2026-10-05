package com.altronixsoft.workflow.quote;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PricingRules.class)
class QuoteConfig {}
