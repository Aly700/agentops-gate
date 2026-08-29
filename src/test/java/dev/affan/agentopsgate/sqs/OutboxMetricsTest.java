package dev.affan.agentopsgate.sqs;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class OutboxMetricsTest {

    @Test
    void backlogGaugeReadsTheCurrentPendingCount() {
        AtomicLong pending = new AtomicLong(3);
        OutboxStore store = new OutboxStore() {
            @Override
            public OutboxMessage storeOutboxMessage(OutboxMessage message) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<OutboxMessage> lockPendingBatch(int batchSize) {
                return List.of();
            }

            @Override
            public long countPending() {
                return pending.get();
            }
        };
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new OutboxMetrics(store).bindTo(registry);

        assertThat(registry.get("gate.outbox.backlog").gauge().value()).isEqualTo(3.0);
        pending.set(7);
        assertThat(registry.get("gate.outbox.backlog").gauge().value()).isEqualTo(7.0);
    }
}
