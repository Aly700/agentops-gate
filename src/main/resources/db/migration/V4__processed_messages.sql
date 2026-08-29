CREATE TABLE processed_messages (
    message_id VARCHAR(128) PRIMARY KEY,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_processed_messages_processed_at ON processed_messages(processed_at);

ALTER TABLE audit_records DROP CONSTRAINT ck_audit_event_type;
ALTER TABLE audit_records ADD CONSTRAINT ck_audit_event_type CHECK (
    event_type IN (
        'POLICY_CREATED',
        'RULE_CREATED',
        'DECISION_CREATED',
        'APPROVAL_CREATED',
        'APPROVAL_APPROVED',
        'APPROVAL_DENIED',
        'APPROVAL_EXPIRED',
        'DLQ_REPLAYED'
    )
);
