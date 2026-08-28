package dev.affan.agentopsgate.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApprovalRepository extends JpaRepository<Approval, UUID> {
    List<Approval> findByStatusAndExpiresAtLessThanEqualOrderByExpiresAtAscIdAsc(
            ApprovalStatus status,
            Instant expiresAt);
}
