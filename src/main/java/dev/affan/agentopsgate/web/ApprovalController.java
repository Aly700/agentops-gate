package dev.affan.agentopsgate.web;

import dev.affan.agentopsgate.domain.Approval;
import dev.affan.agentopsgate.domain.ApprovalService;
import dev.affan.agentopsgate.domain.ApprovalStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/approvals")
public class ApprovalController {

    private final ApprovalService approvalService;

    public ApprovalController(ApprovalService approvalService) {
        this.approvalService = approvalService;
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
}
