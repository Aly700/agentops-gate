package dev.affan.agentopsgate.sim;

import dev.affan.agentopsgate.domain.Approval;
import dev.affan.agentopsgate.domain.ApprovalStatus;
import dev.affan.agentopsgate.domain.AuditEventType;
import dev.affan.agentopsgate.domain.AuditRecord;
import dev.affan.agentopsgate.domain.Decision;
import dev.affan.agentopsgate.domain.Effect;
import dev.affan.agentopsgate.sqs.OutboxMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class Invariants {

    private final InMemoryStores stores;
    private final FaultInjectingBus bus;
    private List<AuditSnapshot> previousAudit = List.of();

    Invariants(InMemoryStores stores, FaultInjectingBus bus) {
        this.stores = stores;
        this.bus = bus;
    }

    void checkAfterStep() {
        requireExactlyOneApprovalPerDecision();
        requireIdempotentDecisionResults();
        requireAppendOnlyOrderedAudit();
        requireAtMostOneConsumerEffect();
    }

    void checkAfterQuiescence() {
        checkAfterStep();
        for (Approval approval : stores.approvals()) {
            require(
                    approval.getStatus() == ApprovalStatus.APPROVED
                            || approval.getStatus() == ApprovalStatus.DENIED
                            || approval.getStatus() == ApprovalStatus.EXPIRED,
                    "approval did not reach a terminal state: " + approval.getId());
            long createdEvents = stores.auditRecords().stream()
                    .filter(record -> record.getAggregateId().equals(approval.getId()))
                    .filter(record -> record.getEventType() == AuditEventType.APPROVAL_CREATED)
                    .count();
            require(createdEvents == 1, "approval must have exactly one APPROVAL_CREATED audit event");
        }
        for (OutboxMessage message : stores.outboxMessages()) {
            require(message.getSentAt() != null, "outbox row was not eventually sent: " + message.getId());
            require(
                    bus.effectCounts().getOrDefault(message.getId(), 0) == 1,
                    "outbox row did not produce exactly one consumer effect: " + message.getId());
            require(
                    stores.processedMessageIds().contains(message.getId().toString()),
                    "outbox row was not claimed by the idempotent consumer: " + message.getId());
        }
        require(bus.pendingMessages() == 0, "message bus did not quiesce");
    }

    private void requireExactlyOneApprovalPerDecision() {
        Map<UUID, Long> approvalCounts = new HashMap<>();
        stores.approvals().forEach(approval -> approvalCounts.merge(approval.getDecisionId(), 1L, Long::sum));
        for (Decision decision : stores.decisions()) {
            long count = approvalCounts.getOrDefault(decision.getId(), 0L);
            if (decision.getEffect() == Effect.REQUIRE_APPROVAL) {
                require(count == 1, "REQUIRE_APPROVAL decision must have exactly one approval: " + decision.getId());
            } else {
                require(count == 0, "non-approval decision unexpectedly has an approval: " + decision.getId());
            }
        }
    }

    private void requireIdempotentDecisionResults() {
        stores.decisionResultsByKey().forEach((key, ids) -> require(
                new HashSet<>(ids).size() <= 1,
                "idempotency key created more than one decision: " + key));
    }

    private void requireAppendOnlyOrderedAudit() {
        List<AuditSnapshot> current = stores.auditRecords().stream().map(AuditSnapshot::from).toList();
        require(current.size() >= previousAudit.size(), "audit log shrank");
        require(
                current.subList(0, previousAudit.size()).equals(previousAudit),
                "an existing audit record was changed or reordered");
        Set<UUID> ids = new HashSet<>();
        Map<Aggregate, Instant> lastTimestamp = new HashMap<>();
        for (AuditSnapshot record : current) {
            require(ids.add(record.id()), "duplicate audit record id: " + record.id());
            Aggregate aggregate = new Aggregate(record.aggregateType(), record.aggregateId());
            Instant previous = lastTimestamp.put(aggregate, record.occurredAt());
            require(
                    previous == null || record.occurredAt().isAfter(previous),
                    "audit records are not strictly ordered for aggregate " + aggregate);
        }
        previousAudit = new ArrayList<>(current);
    }

    private void requireAtMostOneConsumerEffect() {
        bus.effectCounts().forEach((messageId, count) -> require(
                count <= 1,
                "duplicate consumer effect for message " + messageId));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record Aggregate(String type, UUID id) {
    }

    private record AuditSnapshot(
            UUID id,
            AuditEventType eventType,
            String aggregateType,
            UUID aggregateId,
            Instant occurredAt,
            String details) {

        static AuditSnapshot from(AuditRecord record) {
            return new AuditSnapshot(
                    record.getId(),
                    record.getEventType(),
                    record.getAggregateType(),
                    record.getAggregateId(),
                    record.getOccurredAt(),
                    record.getDetails());
        }
    }
}
