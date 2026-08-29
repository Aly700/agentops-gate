package dev.affan.agentopsgate.sqs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class OutboxRelayTest {

    private static final Instant NOW = Instant.parse("2026-08-29T12:00:00Z");
    private final ApprovalMessageCodec codec = new ApprovalMessageCodec(new ObjectMapper());
    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final ApprovalQueuePublisher publisher = mock(ApprovalQueuePublisher.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final OutboxRelay relay = new OutboxRelay(
            outbox,
            publisher,
            codec,
            metrics,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void publishesAndMarksPendingRowsSent() {
        ApprovalMessage approval = approvalMessage();
        OutboxMessage row = pending(codec.encode(approval));
        when(outbox.lockPendingBatch(50)).thenReturn(List.of(row));

        int sent = relay.relayOnce();

        assertThat(sent).isEqualTo(1);
        verify(publisher).publish(approval);
        assertThat(row.getSentAt()).isEqualTo(NOW);
        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getLastError()).isNull();
        assertThat(metrics.counter("gate.outbox.sent").count()).isEqualTo(1.0);
    }

    @Test
    void recordsFailureAndLeavesRowPendingForRetry() {
        ApprovalMessage approval = approvalMessage();
        OutboxMessage row = pending(codec.encode(approval));
        when(outbox.lockPendingBatch(50)).thenReturn(List.of(row));
        org.mockito.Mockito.doThrow(new IllegalStateException("SQS unavailable"))
                .when(publisher).publish(approval);

        int sent = relay.relayOnce();

        assertThat(sent).isZero();
        assertThat(row.getSentAt()).isNull();
        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getLastError()).isEqualTo("SQS unavailable");
        assertThat(metrics.counter("gate.outbox.failed").count()).isEqualTo(1.0);
    }

    private OutboxMessage pending(String payload) {
        return OutboxMessage.pending(
                UUID.randomUUID(),
                "APPROVAL",
                UUID.randomUUID(),
                payload,
                NOW.minusSeconds(10));
    }

    private static ApprovalMessage approvalMessage() {
        return new ApprovalMessage(
                UUID.randomUUID(),
                UUID.randomUUID(),
                NOW.plusSeconds(1800));
    }
}
