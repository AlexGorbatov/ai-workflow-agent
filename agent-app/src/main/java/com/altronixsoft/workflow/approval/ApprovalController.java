package com.altronixsoft.workflow.approval;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Approvals for people with the {@code approver} role (see SecurityConfig). */
@RestController
@RequestMapping("/api/v1/approvals")
class ApprovalController {

    private final ApprovalQueries queries;
    private final ApprovalService service;

    ApprovalController(ApprovalQueries queries, ApprovalService service) {
        this.queries = queries;
        this.service = service;
    }

    /** Without {@code status}: the pending tasks (OPEN and ESCALATED), the most urgent first. */
    @GetMapping
    List<ApprovalViews.Item> list(@RequestParam(required = false) ApprovalStatus status) {
        return queries.list(status);
    }

    @GetMapping("/{id}")
    ApprovalViews.Detail get(@PathVariable UUID id) {
        return queries.get(id);
    }

    @PostMapping("/{id}/decision")
    ApprovalViews.Detail decide(
            @PathVariable UUID id, @Valid @RequestBody Decision decision, Authentication authentication) {
        service.decide(id, decision, authentication.getName());
        return queries.view(id);
    }
}
