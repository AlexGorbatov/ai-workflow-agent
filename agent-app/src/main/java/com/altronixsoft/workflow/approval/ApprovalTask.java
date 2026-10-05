package com.altronixsoft.workflow.approval;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A quote or an investigation waiting for a person. Rows are inserted by {@link ApprovalTaskCreator}; this
 * entity only records what happens next. {@code version} turns a second decision on the same task into a
 * conflict instead of a second signal.
 */
@Entity
@Table(name = "approval_task")
public class ApprovalTask {

    @Id
    private UUID id;

    @Column(name = "instance_id", nullable = false)
    private UUID instanceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ApprovalKind kind;

    @Column(nullable = false)
    private int round;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ApprovalStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> reasons;

    @Column(name = "due_at", nullable = false)
    private Instant dueAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "decided_by")
    private String decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    private String comment;

    @Column(name = "approved_price")
    private BigDecimal approvedPrice;

    private String summary;

    @Version
    private long version;

    protected ApprovalTask() {}

    void escalate() {
        this.status = ApprovalStatus.ESCALATED;
    }

    void decide(ApprovalStatus decision, String by, Instant at, String comment, BigDecimal price) {
        if (!status.isPending()) {
            throw new IllegalStateException("Task " + id + " is already " + status);
        }
        this.status = decision;
        this.decidedBy = by;
        this.decidedAt = at;
        this.comment = comment;
        this.approvedPrice = price;
    }

    void summarize(String text) {
        this.summary = text;
    }

    public UUID getId() {
        return id;
    }

    public UUID getInstanceId() {
        return instanceId;
    }

    public ApprovalKind getKind() {
        return kind;
    }

    public int getRound() {
        return round;
    }

    public ApprovalStatus getStatus() {
        return status;
    }

    public List<String> getReasons() {
        return reasons == null ? List.of() : List.copyOf(reasons);
    }

    public Instant getDueAt() {
        return dueAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public String getComment() {
        return comment;
    }

    public BigDecimal getApprovedPrice() {
        return approvedPrice;
    }

    public String getSummary() {
        return summary;
    }

    public long getVersion() {
        return version;
    }
}
