package com.altronixsoft.workflow.intake;

import com.altronixsoft.workflow.quote.InboundEmail;
import java.util.List;

/** Where customer mail comes from. Returns what is in the inbox, oldest first; deduplication is the caller's job. */
public interface EmailSource {

    List<InboundEmail> fetchNew();
}
