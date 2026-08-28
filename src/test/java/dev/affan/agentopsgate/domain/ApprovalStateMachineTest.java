package dev.affan.agentopsgate.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ApprovalStateMachineTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-28T12:00:00Z");
    private static final Instant EXPIRES_AT = CREATED_AT.plus(30, ChronoUnit.MINUTES);

    @Test
    void approvesAPendingApproval() {
        Approval approval = pendingApproval();
        Instant decidedAt = CREATED_AT.plusSeconds(60);

        approval.approve("reviewer-1", decidedAt);

        assertThat(approval.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(approval.getDecidedBy()).isEqualTo("reviewer-1");
        assertThat(approval.getDecidedAt()).isEqualTo(decidedAt);
    }

    @Test
    void deniesAPendingApproval() {
        Approval approval = pendingApproval();
        Instant decidedAt = CREATED_AT.plusSeconds(60);

        approval.deny("reviewer-1", decidedAt);

        assertThat(approval.getStatus()).isEqualTo(ApprovalStatus.DENIED);
        assertThat(approval.getDecidedBy()).isEqualTo("reviewer-1");
        assertThat(approval.getDecidedAt()).isEqualTo(decidedAt);
    }

    @Test
    void expiresAStalePendingApproval() {
        Approval approval = pendingApproval();

        approval.expire(EXPIRES_AT);

        assertThat(approval.getStatus()).isEqualTo(ApprovalStatus.EXPIRED);
        assertThat(approval.getDecidedBy()).isNull();
        assertThat(approval.getDecidedAt()).isEqualTo(EXPIRES_AT);
    }

    @Test
    void rejectsASecondTerminalTransition() {
        Approval approval = pendingApproval();
        approval.approve("reviewer-1", CREATED_AT.plusSeconds(60));

        assertThatThrownBy(() -> approval.deny("reviewer-2", CREATED_AT.plusSeconds(120)))
                .isInstanceOf(InvalidApprovalTransitionException.class)
                .hasMessageContaining("APPROVED");
    }

    @Test
    void rejectsExpiryBeforeTheDeadline() {
        Approval approval = pendingApproval();

        assertThatThrownBy(() -> approval.expire(EXPIRES_AT.minusMillis(1)))
                .isInstanceOf(InvalidApprovalTransitionException.class)
                .hasMessageContaining("not expired");
    }

    @Test
    void rejectsABlankReviewerIdentity() {
        Approval approval = pendingApproval();

        assertThatThrownBy(() -> approval.approve("  ", CREATED_AT.plusSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decidedBy");
    }

    private static Approval pendingApproval() {
        return Approval.pending(UUID.randomUUID(), UUID.randomUUID(), CREATED_AT, EXPIRES_AT);
    }
}
