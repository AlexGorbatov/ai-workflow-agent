package com.altronixsoft.workflow.engine;

import com.altronixsoft.workflow.quote.QuoteContext;
import com.altronixsoft.workflow.quote.QuoteState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One run of the quote workflow. Only {@code WorkflowEngine} changes it. */
@Entity
@Table(name = "workflow_instance")
public class WorkflowInstance {

    @Id
    private UUID id;

    @Column(name = "workflow_type", nullable = false)
    private String workflowType;

    @Column(name = "business_key", nullable = false)
    private String businessKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private QuoteState state;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private QuoteContext context;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WorkflowInstance() {}

    public WorkflowInstance(
            UUID id, String workflowType, String businessKey, QuoteState state, QuoteContext context, Instant now) {
        this.id = id;
        this.workflowType = workflowType;
        this.businessKey = businessKey;
        this.state = state;
        this.context = context;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void transition(QuoteState newState, QuoteContext newContext, Instant now) {
        this.state = newState;
        this.context = newContext;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getWorkflowType() {
        return workflowType;
    }

    public String getBusinessKey() {
        return businessKey;
    }

    public QuoteState getState() {
        return state;
    }

    public QuoteContext getContext() {
        return context;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
