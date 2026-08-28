CREATE TABLE policies (
    id UUID PRIMARY KEY,
    name VARCHAR(160) NOT NULL CHECK (length(trim(name)) > 0),
    version INTEGER NOT NULL CHECK (version > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_policies_name_version UNIQUE (name, version)
);

CREATE TABLE rules (
    id UUID PRIMARY KEY,
    policy_id UUID NOT NULL REFERENCES policies(id),
    tool_name_glob VARCHAR(255) NOT NULL,
    argument_regex TEXT,
    agent_id VARCHAR(160),
    risk_tier VARCHAR(16),
    effect VARCHAR(32) NOT NULL,
    precedence INTEGER NOT NULL CHECK (precedence >= 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_rules_risk_tier CHECK (
        risk_tier IS NULL OR risk_tier IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')
    ),
    CONSTRAINT ck_rules_effect CHECK (
        effect IN ('ALLOW', 'DENY', 'REQUIRE_APPROVAL')
    ),
    CONSTRAINT uk_rules_policy_precedence UNIQUE (policy_id, precedence)
);

CREATE INDEX idx_rules_policy_order ON rules(policy_id, precedence, id);

CREATE TABLE decisions (
    id UUID PRIMARY KEY,
    policy_id UUID NOT NULL REFERENCES policies(id),
    policy_version INTEGER NOT NULL CHECK (policy_version > 0),
    agent_id VARCHAR(160) NOT NULL,
    tool_name VARCHAR(255) NOT NULL,
    arguments JSONB NOT NULL,
    risk_tier VARCHAR(16) NOT NULL,
    matched_rule_id UUID REFERENCES rules(id),
    effect VARCHAR(32) NOT NULL,
    decided_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_decisions_risk_tier CHECK (
        risk_tier IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')
    ),
    CONSTRAINT ck_decisions_effect CHECK (
        effect IN ('ALLOW', 'DENY', 'REQUIRE_APPROVAL')
    )
);

CREATE INDEX idx_decisions_decided_at ON decisions(decided_at, id);

CREATE TABLE approvals (
    id UUID PRIMARY KEY,
    decision_id UUID NOT NULL UNIQUE REFERENCES decisions(id),
    status VARCHAR(16) NOT NULL,
    decided_by VARCHAR(160),
    decided_at TIMESTAMP WITH TIME ZONE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_approvals_status CHECK (
        status IN ('PENDING', 'APPROVED', 'DENIED', 'EXPIRED')
    ),
    CONSTRAINT ck_approvals_state CHECK (
        (status = 'PENDING' AND decided_by IS NULL AND decided_at IS NULL)
        OR (status IN ('APPROVED', 'DENIED') AND decided_by IS NOT NULL AND decided_at IS NOT NULL)
        OR (status = 'EXPIRED' AND decided_by IS NULL AND decided_at IS NOT NULL)
    )
);

CREATE INDEX idx_approvals_pending_expiry ON approvals(status, expires_at, id);

CREATE TABLE audit_records (
    id UUID PRIMARY KEY,
    event_type VARCHAR(32) NOT NULL,
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id UUID NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    details JSONB NOT NULL,
    CONSTRAINT ck_audit_event_type CHECK (
        event_type IN (
            'POLICY_CREATED',
            'RULE_CREATED',
            'DECISION_CREATED',
            'APPROVAL_CREATED',
            'APPROVAL_APPROVED',
            'APPROVAL_DENIED',
            'APPROVAL_EXPIRED'
        )
    )
);

CREATE INDEX idx_audit_records_time_range ON audit_records(occurred_at, id);
CREATE INDEX idx_audit_records_aggregate ON audit_records(aggregate_type, aggregate_id, occurred_at);

CREATE FUNCTION reject_immutable_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '% is immutable', TG_TABLE_NAME;
END;
$$;

CREATE TRIGGER decisions_are_immutable
    BEFORE UPDATE OR DELETE ON decisions
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_mutation();

CREATE TRIGGER audit_records_are_append_only
    BEFORE UPDATE OR DELETE ON audit_records
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_mutation();
