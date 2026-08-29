package dev.affan.agentopsgate.web;

import dev.affan.agentopsgate.domain.Approval;
import dev.affan.agentopsgate.domain.ApprovalService;
import dev.affan.agentopsgate.domain.ApprovalStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/approvals")
public class ApprovalController {

    private final ApprovalService approvalService;

    public ApprovalController(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @GetMapping("/{id}")
    ApprovalResponse get(@PathVariable UUID id) {
        return ApprovalResponse.from(approvalService.get(id));
    }

    @GetMapping
    ApprovalPageResponse list(
            @RequestParam ApprovalStatus status,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) String cursor) {
        return ApprovalPageResponse.from(approvalService.list(status, limit, cursor));
    }

    @PostMapping("/{id}/approve")
    ApprovalResponse approve(
            @PathVariable UUID id,
            @Valid @RequestBody DecideApprovalRequest request) {
        return ApprovalResponse.from(approvalService.approve(id, request.decidedBy()));
    }

    @PostMapping("/{id}/deny")
    ApprovalResponse deny(
            @PathVariable UUID id,
            @Valid @RequestBody DecideApprovalRequest request) {
        return ApprovalResponse.from(approvalService.deny(id, request.decidedBy()));
    }

    public record DecideApprovalRequest(@NotBlank @Size(max = 160) String decidedBy) {
    }

    public record ApprovalResponse(
            UUID id,
            UUID decisionId,
            ApprovalStatus status,
            String decidedBy,
            Instant decidedAt,
            Instant expiresAt,
            Instant createdAt) {

        static ApprovalResponse from(Approval approval) {
            return new ApprovalResponse(
                    approval.getId(),
                    approval.getDecisionId(),
                    approval.getStatus(),
                    approval.getDecidedBy(),
                    approval.getDecidedAt(),
                    approval.getExpiresAt(),
                    approval.getCreatedAt());
        }
    }

    public record ApprovalPageResponse(
            List<ApprovalResponse> items,
            String nextCursor) {

        static ApprovalPageResponse from(ApprovalService.ApprovalPage page) {
            return new ApprovalPageResponse(
                    page.items().stream().map(ApprovalResponse::from).toList(),
                    page.nextCursor());
        }
    }
}
