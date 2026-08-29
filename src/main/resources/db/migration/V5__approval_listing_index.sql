CREATE INDEX idx_approvals_status_created_at
    ON approvals(status, created_at DESC, id DESC);
