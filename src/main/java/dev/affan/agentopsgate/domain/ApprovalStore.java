package dev.affan.agentopsgate.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApprovalStore {

    Approval storeApproval(Approval approval);

    Optional<Approval> findApprovalById(UUID id);

    List<Approval> findStaleApprovals(ApprovalStatus status, Instant expiresAt);
}
