package com.altronixsoft.workflow.mockcrm;

import com.altronixsoft.workflow.approvaltoken.ApprovalTokens;
import com.altronixsoft.workflow.approvaltoken.InvalidApprovalTokenException;
import com.altronixsoft.workflow.mockcrm.Dtos.CreditStatusDto;
import com.altronixsoft.workflow.mockcrm.Dtos.CustomerDto;
import com.altronixsoft.workflow.mockcrm.Dtos.OpportunityDto;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/** The CRM as MCP tools. The descriptions are what a model (or a person reading the schema) sees. */
@Component
class CrmTools {

    private final CrmStore store;
    private final ApprovalTokens tokens;

    CrmTools(CrmStore store, ApprovalTokens tokens) {
        this.store = store;
        this.tokens = tokens;
    }

    @Tool(
            description = "Find customers by the sender's email address. An exact contact match returns that customer; "
                    + "otherwise every customer on the sender's mail domain. An empty list means a new customer.")
    List<CustomerDto> findCustomersByEmail(@ToolParam(description = "Sender email address") String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("email must not be blank");
        }
        return store.findByEmail(email);
    }

    @Tool(description = "Get credit status and open balance of a customer, including any overdue amount")
    CreditStatusDto getCreditStatus(@ToolParam(description = "Customer id, for example C-1001") String customerId) {
        return store.creditStatus(customerId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown customer " + customerId));
    }

    @Tool(
            description =
                    "Create a sales opportunity. Requires a valid approvalToken and an idempotencyKey; "
                            + "calling again with the same idempotencyKey returns the first opportunity instead of creating another.")
    OpportunityDto createOpportunity(
            @ToolParam(description = "Customer id, for example C-1001") String customerId,
            @ToolParam(description = "Quoted amount") BigDecimal amount,
            @ToolParam(description = "Currency code, for example EUR") String currency,
            @ToolParam(description = "Approval token issued for createOpportunity") String approvalToken,
            @ToolParam(description = "Key that makes retries safe") String idempotencyKey) {
        try {
            tokens.verify(approvalToken, "createOpportunity", amount);
        } catch (InvalidApprovalTokenException e) {
            throw new IllegalArgumentException("Rejected by approval token check: " + e.getMessage());
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        if (!store.exists(customerId)) {
            throw new IllegalArgumentException("Unknown customer " + customerId);
        }
        return store.createOpportunity(idempotencyKey, customerId, amount, currency);
    }
}
