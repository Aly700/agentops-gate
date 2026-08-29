CREATE TABLE idempotency_records (
    key VARCHAR(200) PRIMARY KEY,
    request_hash VARCHAR(64) NOT NULL,
    status_code INTEGER,
    response_body JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_idempotency_response_complete CHECK (
        (status_code IS NULL AND response_body IS NULL)
        OR (status_code IS NOT NULL AND response_body IS NOT NULL)
    )
);

CREATE INDEX idx_idempotency_records_created_at ON idempotency_records(created_at);
