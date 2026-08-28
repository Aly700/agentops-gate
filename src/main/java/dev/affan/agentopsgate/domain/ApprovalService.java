package dev.affan.agentopsgate.domain;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApprovalService {

    private final ApprovalRepository approvals;
    private final AuditService auditService;
    private final Clock clock;

    public ApprovalService(ApprovalRepository approvals, AuditService auditService, Clock clock) {
        this.approvals = approvals;
        this.auditService = auditService;
        this.clock = clock;
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
        List<Approval> stale = approvals.findByStatusAndExpiresAtLessThanEqualOrderByExpiresAtAscIdAsc(
                ApprovalStatus.PENDING,
                now);
        stale.forEach(approval -> {
            approval.expire(now);
            audit(approval, AuditEventType.APPROVAL_EXPIRED);
        });
        return stale.size();
    }

    private Approval requireApproval(UUID id) {
        return approvals.findById(id)
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
