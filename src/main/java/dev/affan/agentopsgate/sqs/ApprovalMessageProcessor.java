package dev.affan.agentopsgate.sqs;

@FunctionalInterface
public interface ApprovalMessageProcessor {

    void process(ApprovalMessage message);
}
