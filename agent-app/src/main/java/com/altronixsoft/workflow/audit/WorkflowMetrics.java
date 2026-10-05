package com.altronixsoft.workflow.audit;

import com.altronixsoft.workflow.quote.QuoteState;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Gauges read from the database: {@code workflow.instances} per state and {@code approvals.open}. They are
 * refreshed on a timer with two grouped queries, not on every scrape. Tags are the state names only.
 */
@Component
public class WorkflowMetrics implements MeterBinder {

    private final JdbcClient jdbc;
    private final Map<QuoteState, AtomicLong> instances = new EnumMap<>(QuoteState.class);
    private final AtomicLong openApprovals = new AtomicLong();

    WorkflowMetrics(JdbcClient jdbc) {
        this.jdbc = jdbc;
        for (QuoteState state : QuoteState.values()) {
            instances.put(state, new AtomicLong());
        }
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        instances.forEach((state, count) -> Gauge.builder("workflow.instances", count, AtomicLong::get)
                .description("Workflow instances per state")
                .tag("state", state.name())
                .register(registry));
        Gauge.builder("approvals.open", openApprovals, AtomicLong::get)
                .description("Approval tasks waiting for a decision (open or escalated)")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${workflow.metrics.refresh:15s}", initialDelay = 0)
    public void refresh() {
        Map<QuoteState, Long> counts = new EnumMap<>(QuoteState.class);
        jdbc.sql("select state, count(*) as n from workflow_instance group by state")
                .query((rs, row) -> Map.entry(QuoteState.valueOf(rs.getString("state")), rs.getLong("n")))
                .list()
                .forEach(e -> counts.put(e.getKey(), e.getValue()));
        instances.forEach((state, count) -> count.set(counts.getOrDefault(state, 0L)));
        openApprovals.set(jdbc.sql("select count(*) from approval_task where status in ('OPEN', 'ESCALATED')")
                .query(Long.class)
                .single());
    }
}
