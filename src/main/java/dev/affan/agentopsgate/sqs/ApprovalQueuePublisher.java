package dev.affan.agentopsgate.sqs;

public interface ApprovalQueuePublisher {
    void publish(ApprovalMessage message);
}
