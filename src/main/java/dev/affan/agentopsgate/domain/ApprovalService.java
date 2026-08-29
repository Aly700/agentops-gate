package dev.affan.agentopsgate.domain;

import dev.affan.agentopsgate.sqs.ApprovalMessage;
import dev.affan.agentopsgate.sqs.ApprovalMessageProcessor;
import dev.affan.agentopsgate.sqs.ApprovalMessageValidator;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApprovalService implements ApprovalMessageProcessor {

    private final ApprovalStore approvals;
    private final AuditService auditService;
    private final Clock clock;
    private final ApprovalMessageValidator messageValidator;

    public ApprovalService(
            ApprovalStore approvals,
            AuditService auditService,
            Clock clock,
            ApprovalMessageValidator messageValidator) {
        this.approvals = approvals;
        this.auditService = auditService;
        this.clock = clock;
        this.messageValidator = messageValidator;
    }

    @Transactional
    public Approval approve(UUID id, String decidedBy) {
        Approval approval = requireApproval(id);
        approval.approve(decidedBy, clock.instant());
        audit(approval, AuditEventType.APPROVAL_APPROVED);
        return approval;
    }

    @Transactional
    public Approval deny(UUID id, String decidedBy) {
        Approval approval = requireApproval(id);
        approval.deny(decidedBy, clock.instant());
        audit(approval, AuditEventType.APPROVAL_DENIED);
        return approval;
    }

    @Transactional
    public int expireStale() {
        Instant now = clock.instant();
        List<Approval> stale = approvals.findStaleApprovals(
                ApprovalStatus.PENDING,
                now);
        stale.forEach(approval -> {
            approval.expire(now);
            audit(approval, AuditEventType.APPROVAL_EXPIRED);
        });
        return stale.size();
    }

    @Override
    @Transactional
    public void process(ApprovalMessage message) {
        Approval approval = requireApproval(message.approvalId());
        messageValidator.validate(message, approval);
        if (approval.getStatus() != ApprovalStatus.PENDING) {
            return;
        }
        Instant now = clock.instant();
        if (!now.isBefore(approval.getExpiresAt())) {
            approval.expire(now);
            audit(approval, AuditEventType.APPROVAL_EXPIRED);
        }
    }

    private Approval requireApproval(UUID id) {
        return approvals.findApprovalById(id)
                .orElseThrow(() -> new ResourceNotFoundException("approval", id));
    }

    private void audit(Approval approval, AuditEventType eventType) {
        auditService.append(
                eventType,
                "APPROVAL",
                approval.getId(),
                Map.of(
                        "decisionId", approval.getDecisionId(),
                        "status", approval.getStatus()));
    }
}
