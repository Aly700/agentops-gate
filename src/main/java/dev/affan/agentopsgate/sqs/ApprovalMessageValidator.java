package dev.affan.agentopsgate.sqs;

import dev.affan.agentopsgate.domain.Approval;
import org.springframework.stereotype.Component;

@Component
public final class ApprovalMessageValidator {

    public void validate(ApprovalMessage message, Approval approval) {
        if (!approval.getId().equals(message.approvalId())) {
            throw new IllegalArgumentException("approvalId does not match the persisted approval");
        }
        if (!approval.getDecisionId().equals(message.decisionId())) {
            throw new IllegalArgumentException("decisionId does not match the persisted approval");
        }
        if (!approval.getExpiresAt().equals(message.expiresAt())) {
            throw new IllegalArgumentException("expiresAt does not match the persisted approval");
        }
    }
}
