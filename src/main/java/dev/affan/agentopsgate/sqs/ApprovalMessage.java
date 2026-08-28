package dev.affan.agentopsgate.sqs;

import java.time.Instant;
import java.util.UUID;

public record ApprovalMessage(UUID approvalId, UUID decisionId, Instant expiresAt) {
}
