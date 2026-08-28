package dev.affan.agentopsgate.export;

import java.time.Clock;
import java.time.LocalDate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "agentops.export.enabled", havingValue = "true")
public final class AuditExportJob {

    private final AuditExportService auditExportService;
    private final Clock clock;

    public AuditExportJob(AuditExportService auditExportService, Clock clock) {
        this.auditExportService = auditExportService;
        this.clock = clock;
    }

    @Scheduled(cron = "${agentops.export.cron:0 5 0 * * *}", zone = "UTC")
    public void exportPreviousUtcDay() {
        auditExportService.export(LocalDate.now(clock).minusDays(1));
    }
}
