package dev.affan.agentopsgate.sqs;

import java.util.List;

public interface OutboxStore {

    OutboxMessage storeOutboxMessage(OutboxMessage message);

    List<OutboxMessage> lockPendingBatch(int batchSize);
}
