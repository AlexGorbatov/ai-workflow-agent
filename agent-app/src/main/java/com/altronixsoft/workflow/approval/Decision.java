package com.altronixsoft.workflow.approval;

import com.altronixsoft.workflow.quote.QuoteState;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * What an approver decided.
 *
 * @param action APPROVE, REJECT or RETRY
 * @param editedPrice APPROVE only: a price other than the quoted one; never below cost
 * @param comment required for REJECT
 * @param retryFrom RETRY only: UNDERSTOOD (read the email again) or ENRICHED (price again)
 */
public record Decision(
        @NotNull Action action,

        @DecimalMin(value = "0.01") @Digits(integer = 10, fraction = 2)
        BigDecimal editedPrice,

        @Size(max = 2000) String comment,
        QuoteState retryFrom) {

    public enum Action {
        APPROVE,
        REJECT,
        RETRY
    }

    @AssertTrue(message = "a comment is required to reject")
    boolean isCommentGivenForReject() {
        return action != Action.REJECT || (comment != null && !comment.isBlank());
    }

    @AssertTrue(message = "retryFrom must be UNDERSTOOD or ENRICHED")
    boolean isRetryFromValid() {
        return action != Action.RETRY || retryFrom == QuoteState.UNDERSTOOD || retryFrom == QuoteState.ENRICHED;
    }

    @AssertTrue(message = "editedPrice is only for APPROVE")
    boolean isPriceOnlyForApprove() {
        return editedPrice == null || action == Action.APPROVE;
    }
}
