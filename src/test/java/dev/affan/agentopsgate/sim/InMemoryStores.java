package dev.affan.agentopsgate.sim;

import dev.affan.agentopsgate.domain.Approval;
import dev.affan.agentopsgate.domain.ApprovalStatus;
import dev.affan.agentopsgate.domain.ApprovalStore;
import dev.affan.agentopsgate.domain.AuditRecord;
import dev.affan.agentopsgate.domain.AuditStore;
import dev.affan.agentopsgate.domain.Decision;
import dev.affan.agentopsgate.domain.DecisionOutcome;
import dev.affan.agentopsgate.domain.DecisionStore;
import dev.affan.agentopsgate.domain.Policy;
import dev.affan.agentopsgate.domain.PolicyStore;
import dev.affan.agentopsgate.domain.Rule;
import dev.affan.agentopsgate.domain.RuleStore;
import dev.affan.agentopsgate.sqs.OutboxMessage;
import dev.affan.agentopsgate.sqs.OutboxStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

final class InMemoryStores
        implements PolicyStore, RuleStore, DecisionStore, ApprovalStore, AuditStore, OutboxStore {

    private final Trace trace;
    private final InstantSource now;
    private final Map<UUID, Policy> policies = new LinkedHashMap<>();
    private final Map<UUID, Rule> rules = new LinkedHashMap<>();
    private final Map<UUID, Decision> decisions = new LinkedHashMap<>();
    private final Map<UUID, Approval> approvals = new LinkedHashMap<>();
    private final Map<UUID, AuditRecord> auditRecords = new LinkedHashMap<>();
    private final Map<UUID, OutboxMessage> outboxMessages = new LinkedHashMap<>();
    private final Set<String> processedMessageIds = new LinkedHashSet<>();
    private final Map<String, DecisionOutcome> idempotentOutcomes = new LinkedHashMap<>();
    private final Map<String, List<UUID>> decisionResultsByKey = new LinkedHashMap<>();

    InMemoryStores(Trace trace, InstantSource now) {
        this.trace = trace;
        this.now = now;
    }

    PolicyStore policyStore() {
        return this;
    }

    RuleStore ruleStore() {
        return this;
    }

    DecisionStore decisionStore() {
        return this;
    }

    ApprovalStore approvalStore() {
        return this;
    }

    AuditStore auditStore() {
        return this;
    }

    OutboxStore outboxStore() {
        return this;
    }

    void seedPolicy(Policy policy) {
        policies.put(policy.getId(), policy);
    }

    void seedRule(Rule rule) {
        rules.put(rule.getId(), rule);
    }

    Collection<Decision> decisions() {
        return List.copyOf(decisions.values());
    }

    Collection<Approval> approvals() {
        return List.copyOf(approvals.values());
    }

    List<AuditRecord> auditRecords() {
        return List.copyOf(auditRecords.values());
    }

    Collection<OutboxMessage> outboxMessages() {
        return List.copyOf(outboxMessages.values());
    }

    Set<String> processedMessageIds() {
        return Set.copyOf(processedMessageIds);
    }

    Map<String, List<UUID>> decisionResultsByKey() {
        Map<String, List<UUID>> copy = new LinkedHashMap<>();
        decisionResultsByKey.forEach((key, ids) -> copy.put(key, List.copyOf(ids)));
        return copy;
    }

    DecisionOutcome evaluateIdempotently(String key, Supplier<DecisionOutcome> operation, Instant at) {
        DecisionOutcome outcome = idempotentOutcomes.get(key);
        if (outcome == null) {
            outcome = operation.get();
            idempotentOutcomes.put(key, outcome);
            trace.record(at, "idempotency-claim key=" + key + " decision=" + outcome.decision().getId());
        } else {
            trace.record(at, "idempotency-replay key=" + key + " decision=" + outcome.decision().getId());
        }
        decisionResultsByKey.computeIfAbsent(key, ignored -> new ArrayList<>())
                .add(outcome.decision().getId());
        return outcome;
    }

    JdbcTemplate processedMessagesJdbc(InstantSource clock) {
        return new JdbcTemplate() {
            @Override
            public int update(String sql, Object... args) {
                String messageId = (String) args[0];
                boolean inserted = processedMessageIds.add(messageId);
                trace.record(clock.instant(), (inserted ? "processed-claim " : "processed-duplicate ") + messageId);
                return inserted ? 1 : 0;
            }
        };
    }

    PlatformTransactionManager transactionManager() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SnapshotTransactionStatus(processedMessageIds);
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
                SnapshotTransactionStatus snapshot = (SnapshotTransactionStatus) status;
                processedMessageIds.clear();
                processedMessageIds.addAll(snapshot.processedMessageIds());
            }
        };
    }

    @Override
    public Optional<Policy> findPolicyById(UUID id) {
        return Optional.ofNullable(policies.get(id));
    }

    @Override
    public List<Rule> findRulesByPolicyId(UUID policyId) {
        return rules.values().stream()
                .filter(rule -> rule.getPolicyId().equals(policyId))
                .sorted(Comparator.comparingInt(Rule::getPrecedence).thenComparing(Rule::getId))
                .toList();
    }

    @Override
    public Decision storeDecision(Decision decision) {
        decisions.put(decision.getId(), decision);
        trace.record(now.instant(), "decision-saved id=" + decision.getId());
        return decision;
    }

    @Override
    public Optional<Decision> findDecisionById(UUID id) {
        return Optional.ofNullable(decisions.get(id));
    }

    @Override
    public Approval storeApproval(Approval approval) {
        boolean decisionAlreadyHasApproval = approvals.values().stream()
                .anyMatch(existing -> existing.getDecisionId().equals(approval.getDecisionId()));
        if (decisionAlreadyHasApproval) {
            throw new IllegalStateException("decision already has an approval");
        }
        approvals.put(approval.getId(), approval);
        trace.record(now.instant(), "approval-saved id=" + approval.getId());
        return approval;
    }

    @Override
    public Optional<Approval> findApprovalById(UUID id) {
        return Optional.ofNullable(approvals.get(id));
    }

    @Override
    public List<Approval> findStaleApprovals(ApprovalStatus status, Instant expiresAt) {
        return approvals.values().stream()
                .filter(approval -> approval.getStatus() == status)
                .filter(approval -> !approval.getExpiresAt().isAfter(expiresAt))
                .sorted(Comparator.comparing(Approval::getExpiresAt).thenComparing(Approval::getId))
                .toList();
    }

    @Override
    public AuditRecord storeAuditRecord(AuditRecord record) {
        if (auditRecords.putIfAbsent(record.getId(), record) != null) {
            throw new IllegalStateException("audit record already exists: " + record.getId());
        }
        trace.record(now.instant(), "audit-appended event=" + record.getEventType()
                + " aggregate=" + record.getAggregateId());
        return record;
    }

    @Override
    public Optional<AuditRecord> findAuditRecordById(UUID id) {
        return Optional.ofNullable(auditRecords.get(id));
    }

    @Override
    public List<AuditRecord> findAuditRecords(Instant from, Instant to) {
        return auditRecords.values().stream()
                .filter(record -> !record.getOccurredAt().isBefore(from))
                .filter(record -> record.getOccurredAt().isBefore(to))
                .sorted(Comparator.comparing(AuditRecord::getOccurredAt).thenComparing(AuditRecord::getId))
                .toList();
    }

    @Override
    public OutboxMessage storeOutboxMessage(OutboxMessage message) {
        outboxMessages.put(message.getId(), message);
        trace.record(now.instant(), "outbox-saved id=" + message.getId());
        return message;
    }

    @Override
    public List<OutboxMessage> lockPendingBatch(int batchSize) {
        return outboxMessages.values().stream()
                .filter(message -> message.getSentAt() == null)
                .sorted(Comparator.comparing(OutboxMessage::getCreatedAt).thenComparing(OutboxMessage::getId))
                .limit(batchSize)
                .toList();
    }

    @FunctionalInterface
    interface InstantSource {
        Instant instant();
    }

    private static final class SnapshotTransactionStatus extends SimpleTransactionStatus {

        private final Set<String> processedMessageIds;

        private SnapshotTransactionStatus(Set<String> processedMessageIds) {
            this.processedMessageIds = Set.copyOf(processedMessageIds);
        }

        Set<String> processedMessageIds() {
            return processedMessageIds;
        }
    }
}
