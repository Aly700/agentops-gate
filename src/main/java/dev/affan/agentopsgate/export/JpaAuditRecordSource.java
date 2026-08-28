package dev.affan.agentopsgate.export;

import dev.affan.agentopsgate.domain.AuditRecord;
import dev.affan.agentopsgate.domain.AuditRecordRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public final class JpaAuditRecordSource implements AuditRecordSource {

    private final AuditRecordRepository auditRecords;

    public JpaAuditRecordSource(AuditRecordRepository auditRecords) {
        this.auditRecords = auditRecords;
    }

    @Override
    public List<AuditRecord> find(Instant from, Instant to) {
        return auditRecords.findByOccurredAtGreaterThanEqualAndOccurredAtLessThanOrderByOccurredAtAscIdAsc(from, to);
    }
}
