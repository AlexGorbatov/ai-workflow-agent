package com.altronixsoft.workflow.approvaltoken;

import com.altronixsoft.workflow.approvaltoken.InvalidApprovalTokenException.Reason;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Issues and verifies approval tokens: {@code base64url(payload).base64url(HMAC-SHA256(payload))}.
 * The payload is five newline-separated fields, so no JSON library is needed on either side.
 */
public final class ApprovalTokens {

    private static final int MIN_SECRET_LENGTH = 32;
    private static final String HMAC = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] secret;
    private final Clock clock;

    public ApprovalTokens(String secret, Clock clock) {
        if (secret == null || secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalArgumentException(
                    "The approval token secret must be at least " + MIN_SECRET_LENGTH + " characters");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    public String issue(ApprovalClaims claims) {
        String payload = String.join(
                "\n",
                field(claims.instanceId() == null ? "" : claims.instanceId().toString()),
                field(claims.action()),
                field(claims.maxAmount() == null ? "" : claims.maxAmount().toPlainString()),
                field(claims.approvedBy()),
                field(String.valueOf(claims.expiresAt().getEpochSecond())));
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        return ENCODER.encodeToString(bytes) + "." + ENCODER.encodeToString(sign(bytes));
    }

    /**
     * Checks signature, expiry, action and amount, in that order, and returns the claims.
     *
     * @param amount what the caller is about to do; null when the action carries no amount
     */
    public ApprovalClaims verify(String token, String action, BigDecimal amount) {
        ApprovalClaims claims = parse(token);
        if (!claims.expiresAt().isAfter(clock.instant())) {
            throw new InvalidApprovalTokenException(Reason.EXPIRED, "The approval token has expired");
        }
        if (!claims.action().equals(action)) {
            throw new InvalidApprovalTokenException(Reason.WRONG_ACTION, "The approval token is not for " + action);
        }
        if (claims.maxAmount() != null && (amount == null || amount.compareTo(claims.maxAmount()) > 0)) {
            throw new InvalidApprovalTokenException(
                    Reason.AMOUNT_EXCEEDED, "The amount is above what the approval token allows");
        }
        return claims;
    }

    private ApprovalClaims parse(String token) {
        if (token == null) {
            throw malformed();
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 2) {
            throw malformed();
        }
        byte[] payload;
        byte[] signature;
        try {
            payload = DECODER.decode(parts[0]);
            signature = DECODER.decode(parts[1]);
        } catch (IllegalArgumentException e) {
            throw malformed();
        }
        // constant-time comparison: the check must not reveal how many leading bytes were right
        if (!MessageDigest.isEqual(sign(payload), signature)) {
            throw new InvalidApprovalTokenException(Reason.BAD_SIGNATURE, "The approval token signature is wrong");
        }
        String[] fields = new String(payload, StandardCharsets.UTF_8).split("\n", -1);
        if (fields.length != 5) {
            throw malformed();
        }
        try {
            return new ApprovalClaims(
                    fields[0].isEmpty() ? null : UUID.fromString(fields[0]),
                    fields[1],
                    fields[2].isEmpty() ? null : new BigDecimal(fields[2]),
                    fields[3],
                    Instant.ofEpochSecond(Long.parseLong(fields[4])));
        } catch (IllegalArgumentException e) {
            throw malformed();
        }
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret, HMAC));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }

    private static String field(String value) {
        if (value == null || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("An approval claim must be a single line of text");
        }
        return value;
    }

    private static InvalidApprovalTokenException malformed() {
        return new InvalidApprovalTokenException(Reason.MALFORMED, "The approval token is malformed");
    }
}
