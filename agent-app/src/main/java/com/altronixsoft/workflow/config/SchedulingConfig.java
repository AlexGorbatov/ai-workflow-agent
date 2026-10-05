package com.altronixsoft.workflow.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on {@code @Scheduled} for the intake poller and the outbox relay. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SchedulingConfig {}
