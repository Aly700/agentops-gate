package dev.affan.agentopsgate.export;

import dev.affan.agentopsgate.domain.AuditRecord;
import java.time.Instant;
import java.util.List;

@FunctionalInterface
public interface AuditRecordSource {
    List<AuditRecord> find(Instant from, Instant to);
}
