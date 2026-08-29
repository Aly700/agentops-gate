package dev.affan.agentopsgate.domain;

import dev.affan.agentopsgate.sqs.ApprovalMessage;
import dev.affan.agentopsgate.sqs.ApprovalMessageProcessor;
import dev.affan.agentopsgate.sqs.ApprovalMessageValidator;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
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

    @Transactional(readOnly = true)
    public Approval get(UUID id) {
        return requireApproval(id);
    }

    @Transactional(readOnly = true)
    public ApprovalPage list(ApprovalStatus status, int limit, String cursor) {
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        ApprovalCursor decodedCursor = decodeCursor(cursor);
        List<Approval> fetched = approvals.findApprovals(
                status,
                decodedCursor == null ? null : decodedCursor.createdAt(),
                decodedCursor == null ? null : decodedCursor.id(),
                limit + 1);
        boolean hasNext = fetched.size() > limit;
        List<Approval> items = hasNext ? List.copyOf(fetched.subList(0, limit)) : List.copyOf(fetched);
        String nextCursor = hasNext ? encodeCursor(items.getLast()) : null;
        return new ApprovalPage(items, nextCursor);
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

    private static String encodeCursor(Approval approval) {
        String value = approval.getCreatedAt() + "|" + approval.getId();
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static ApprovalCursor decodeCursor(String cursor) {
        if (cursor == null) {
            return null;
        }
        try {
            String decoded = new String(
                    Base64.getUrlDecoder().decode(cursor),
                    StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", -1);
            if (parts.length != 2) {
                throw new IllegalArgumentException("cursor is invalid");
            }
            return new ApprovalCursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("cursor is invalid", exception);
        }
    }

    public record ApprovalPage(List<Approval> items, String nextCursor) {

        public ApprovalPage {
            items = List.copyOf(items);
        }
    }

    private record ApprovalCursor(Instant createdAt, UUID id) {
    }
}
