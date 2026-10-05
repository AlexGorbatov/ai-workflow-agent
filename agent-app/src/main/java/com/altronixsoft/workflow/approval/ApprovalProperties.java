package com.altronixsoft.workflow.approval;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** @param escalationTo who gets the mail when an approval task is still open at its SLA */
@ConfigurationProperties("workflow.approvals")
public record ApprovalProperties(String escalationTo) {

    public ApprovalProperties {
        escalationTo = escalationTo == null ? "approvals-lead@nordline.test" : escalationTo;
    }
}
