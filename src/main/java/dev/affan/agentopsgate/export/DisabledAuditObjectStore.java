package dev.affan.agentopsgate.export;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "agentops.aws.enabled", havingValue = "false", matchIfMissing = true)
public final class DisabledAuditObjectStore implements AuditObjectStore {

    @Override
    public void put(String objectKey, byte[] jsonLines) {
        throw new ExportUnavailableException("S3 audit export is disabled");
    }
}
