package dev.affan.agentopsgate.export;

import java.time.LocalDate;

public record AuditExportResult(LocalDate date, String objectKey, int recordCount) {
}
