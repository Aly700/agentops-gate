package dev.affan.agentopsgate.export;

public interface AuditObjectStore {
    void put(String objectKey, byte[] jsonLines);
}
