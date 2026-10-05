package com.altronixsoft.workflow.approvaltoken;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * What an approval token permits: one {@code action}, for one instance, up to {@code maxAmount} (null: the
 * action carries no amount), approved by {@code approvedBy} until {@code expiresAt}.
 */
public record ApprovalClaims(
        UUID instanceId, String action, BigDecimal maxAmount, String approvedBy, Instant expiresAt) {}
