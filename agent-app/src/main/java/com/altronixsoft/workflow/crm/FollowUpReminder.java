package com.altronixsoft.workflow.crm;

import com.altronixsoft.workflow.engine.FollowUpHandler;
import com.altronixsoft.workflow.outbox.MailTemplates;
import com.altronixsoft.workflow.outbox.OutboxService;
import com.altronixsoft.workflow.quote.QuoteContext;
import com.altronixsoft.workflow.quote.QuoteFacts;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** The reminder after a quote nobody answered: a fixed template, no model, in the thread of the quote mail. */
@Component
class FollowUpReminder implements FollowUpHandler {

    private final MailTemplates templates;
    private final OutboxService outbox;
    private final FollowUpProperties properties;

    FollowUpReminder(MailTemplates templates, OutboxService outbox, FollowUpProperties properties) {
        this.templates = templates;
        this.outbox = outbox;
        this.properties = properties;
    }

    @Override
    public Duration remind(UUID instanceId, QuoteContext ctx) {
        QuoteFacts facts = QuoteFacts.from(ctx);
        MailTemplates.Mail mail = templates.render("followup", facts.language(), facts.placeholders());
        String thread = ctx.outboundMessageId() != null
                ? ctx.outboundMessageId()
                : ctx.email().messageId();
        outbox.enqueueEmail(instanceId, "FOLLOW-UP-1", ctx.email().from(), mail.subject(), mail.body(), thread);
        return properties.after();
    }
}
