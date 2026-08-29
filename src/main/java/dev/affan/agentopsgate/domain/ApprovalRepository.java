package dev.affan.agentopsgate.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApprovalRepository extends JpaRepository<Approval, UUID>, ApprovalStore {
    List<Approval> findByStatusAndExpiresAtLessThanEqualOrderByExpiresAtAscIdAsc(
            ApprovalStatus status,
            Instant expiresAt);

    @Query(value = """
            SELECT *
            FROM approvals
            WHERE status = :status
            ORDER BY created_at DESC, id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Approval> findFirstPageByStatus(
            @Param("status") String status,
            @Param("limit") int limit);

    @Query(value = """
            SELECT *
            FROM approvals
            WHERE status = :status
              AND (created_at < :cursorCreatedAt
                   OR (created_at = :cursorCreatedAt AND id < :cursorId))
            ORDER BY created_at DESC, id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Approval> findPageByStatusAfter(
            @Param("status") String status,
            @Param("cursorCreatedAt") Instant cursorCreatedAt,
            @Param("cursorId") UUID cursorId,
            @Param("limit") int limit);

    @Override
    default Approval storeApproval(Approval approval) {
        return save(approval);
    }

    @Override
    default java.util.Optional<Approval> findApprovalById(UUID id) {
        return findById(id);
    }

    @Override
    default List<Approval> findApprovals(
            ApprovalStatus status,
            Instant cursorCreatedAt,
            UUID cursorId,
            int limit) {
        if (cursorCreatedAt == null) {
            return findFirstPageByStatus(status.name(), limit);
        }
        return findPageByStatusAfter(status.name(), cursorCreatedAt, cursorId, limit);
    }

    @Override
    default List<Approval> findStaleApprovals(ApprovalStatus status, Instant expiresAt) {
        return findByStatusAndExpiresAtLessThanEqualOrderByExpiresAtAscIdAsc(status, expiresAt);
    }
}
