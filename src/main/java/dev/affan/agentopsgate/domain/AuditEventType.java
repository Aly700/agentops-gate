package dev.affan.agentopsgate.domain;

public enum AuditEventType {
    POLICY_CREATED,
    RULE_CREATED,
    DECISION_CREATED,
    APPROVAL_CREATED,
    APPROVAL_APPROVED,
    APPROVAL_DENIED,
    APPROVAL_EXPIRED,
    DLQ_REPLAYED
}
