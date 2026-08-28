package dev.affan.agentopsgate.export;

import dev.affan.agentopsgate.domain.AuditRecord;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class AuditExportService {

    private final AuditRecordSource auditRecords;
    private final AuditObjectStore objectStore;
    private final ObjectMapper objectMapper;

    public AuditExportService(
            AuditRecordSource auditRecords,
            AuditObjectStore objectStore,
            ObjectMapper objectMapper) {
        this.auditRecords = auditRecords;
        this.objectStore = objectStore;
        this.objectMapper = objectMapper;
    }

    public AuditExportResult export(LocalDate date) {
        Instant from = date.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant to = date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        List<AuditRecord> records = auditRecords.find(from, to);
        String objectKey = "audit/year=%04d/month=%02d/day=%02d/audit.jsonl".formatted(
                date.getYear(),
                date.getMonthValue(),
                date.getDayOfMonth());
        objectStore.put(objectKey, serialize(records));
        return new AuditExportResult(date, objectKey, records.size());
    }

    private byte[] serialize(List<AuditRecord> records) {
        StringBuilder jsonLines = new StringBuilder();
        for (AuditRecord record : records) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", record.getId());
            value.put("eventType", record.getEventType());
            value.put("aggregateType", record.getAggregateType());
            value.put("aggregateId", record.getAggregateId());
            value.put("occurredAt", record.getOccurredAt());
            value.put("details", objectMapper.readTree(record.getDetails()));
            jsonLines.append(objectMapper.writeValueAsString(value)).append('\n');
        }
        return jsonLines.toString().getBytes(StandardCharsets.UTF_8);
    }
}
