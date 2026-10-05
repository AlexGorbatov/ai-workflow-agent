package com.altronixsoft.workflow.crm;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** @param after how long to wait for the customer's answer, after the quote and again after the reminder */
@ConfigurationProperties("workflow.follow-up")
public record FollowUpProperties(Duration after) {

    public FollowUpProperties {
        after = after == null ? Duration.ofHours(72) : after;
    }
}
