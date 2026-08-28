package dev.affan.agentopsgate.sqs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "agentops.aws.enabled", havingValue = "false", matchIfMissing = true)
public final class DisabledApprovalQueuePublisher implements ApprovalQueuePublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(DisabledApprovalQueuePublisher.class);

    @Override
    public void publish(ApprovalMessage message) {
        LOGGER.warn(
                "event=approval_queue_disabled approval_id={} decision_id={}",
                message.approvalId(),
                message.decisionId());
    }
}
