package dev.affan.agentopsgate.sqs;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

@Component
public final class OutboxMetrics implements MeterBinder {

    private final OutboxStore outbox;

    public OutboxMetrics(OutboxStore outbox) {
        this.outbox = outbox;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("gate.outbox.backlog", outbox, OutboxStore::countPending)
                .description("Transactional outbox rows awaiting successful publication")
                .register(registry);
    }
}
