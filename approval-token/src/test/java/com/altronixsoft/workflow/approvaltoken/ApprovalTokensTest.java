package com.altronixsoft.workflow.approvaltoken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.altronixsoft.workflow.approvaltoken.InvalidApprovalTokenException.Reason;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ApprovalTokensTest {

    private static final String SECRET = "unit-test-secret-that-is-long-enough-0123456789";
    private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ApprovalTokens tokens = new ApprovalTokens(SECRET, clock);
    private final UUID instance = UUID.randomUUID();

    private ApprovalClaims claims() {
        return new ApprovalClaims(
                instance, "createOpportunity", new BigDecimal("1210.00"), "user:olena", NOW.plus(Duration.ofHours(1)));
    }

    private static Reason reasonOf(Runnable action) {
        try {
            action.run();
        } catch (InvalidApprovalTokenException e) {
            return e.reason();
        }
        throw new AssertionError("expected the token to be refused");
    }

    @Test
    void aTokenCarriesItsClaimsThroughIssueAndVerify() {
        String token = tokens.issue(claims());

        ApprovalClaims back = tokens.verify(token, "createOpportunity", new BigDecimal("1210.00"));

        assertThat(back).isEqualTo(claims());
    }

    @Test
    void anAmountBelowTheLimitIsAccepted() {
        String token = tokens.issue(claims());

        assertThat(tokens.verify(token, "createOpportunity", new BigDecimal("999.99"))
                        .maxAmount())
                .isEqualByComparingTo("1210.00");
    }

    @Test
    void anAmountAboveTheLimitIsRefused() {
        String token = tokens.issue(claims());

        assertThat(reasonOf(() -> tokens.verify(token, "createOpportunity", new BigDecimal("1210.01"))))
                .isEqualTo(Reason.AMOUNT_EXCEEDED);
    }

    @Test
    void aTokenForAnotherActionIsRefused() {
        String token = tokens.issue(claims());

        assertThat(reasonOf(() -> tokens.verify(token, "deleteCustomer", new BigDecimal("1"))))
                .isEqualTo(Reason.WRONG_ACTION);
    }

    @Test
    void anExpiredTokenIsRefused() {
        String token = tokens.issue(claims());
        ApprovalTokens later = new ApprovalTokens(SECRET, Clock.fixed(NOW.plus(Duration.ofHours(1)), ZoneOffset.UTC));

        assertThat(reasonOf(() -> later.verify(token, "createOpportunity", new BigDecimal("1"))))
                .isEqualTo(Reason.EXPIRED);
    }

    @Test
    void aTokenSignedWithAnotherSecretIsRefused() {
        String token = new ApprovalTokens("another-secret-that-is-also-long-enough-987654321", clock).issue(claims());

        assertThat(reasonOf(() -> tokens.verify(token, "createOpportunity", new BigDecimal("1"))))
                .isEqualTo(Reason.BAD_SIGNATURE);
    }

    @Test
    void changingAnyPartOfThePayloadBreaksTheSignature() {
        String token = tokens.issue(claims());
        String[] parts = token.split("\\.");
        String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[0]));
        String raised = payload.replace("1210.00", "9999.00");
        String forged =
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raised.getBytes()) + "." + parts[1];

        assertThat(raised).isNotEqualTo(payload);
        assertThat(reasonOf(() -> tokens.verify(forged, "createOpportunity", new BigDecimal("5000"))))
                .isEqualTo(Reason.BAD_SIGNATURE);
    }

    @Test
    void garbageIsRefusedAsMalformedWithoutAnyOtherException() {
        for (String garbage : new String[] {null, "", "abc", "a.b.c", "....", "!!!.???", "YQ.YQ"}) {
            assertThat(reasonOf(() -> tokens.verify(garbage, "createOpportunity", BigDecimal.ONE)))
                    .as("input %s", garbage)
                    .isIn(Reason.MALFORMED, Reason.BAD_SIGNATURE);
        }
    }

    @Test
    void aMissingAmountIsRefusedWhenTheTokenSetsALimit() {
        String token = tokens.issue(claims());

        assertThat(reasonOf(() -> tokens.verify(token, "createOpportunity", null)))
                .isEqualTo(Reason.AMOUNT_EXCEEDED);
    }

    @Test
    void aTokenWithoutALimitAcceptsActionsThatCarryNoAmount() {
        String token = tokens.issue(new ApprovalClaims(instance, "sendNote", null, "user:olena", NOW.plusSeconds(60)));

        assertThat(tokens.verify(token, "sendNote", null).approvedBy()).isEqualTo("user:olena");
    }

    @Test
    void theSecretMustBeLongEnough() {
        assertThatThrownBy(() -> new ApprovalTokens("short", clock)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApprovalTokens(null, clock)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void claimsThatCannotBeEncodedAreRefusedAtIssue() {
        ApprovalClaims bad =
                new ApprovalClaims(instance, "create\nOpportunity", BigDecimal.ONE, "user", NOW.plusSeconds(1));

        assertThatThrownBy(() -> tokens.issue(bad)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theSameClaimsAlwaysProduceTheSameToken() {
        assertThat(tokens.issue(claims())).isEqualTo(tokens.issue(claims()));
    }
}
