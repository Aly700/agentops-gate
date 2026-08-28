package dev.affan.agentopsgate.sqs;

import dev.affan.agentopsgate.config.AwsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

@Component
@ConditionalOnProperty(
        name = {"agentops.aws.enabled", "agentops.aws.sqs.worker-enabled"},
        havingValue = "true")
public final class ApprovalQueueWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApprovalQueueWorker.class);

    private final SqsClient sqsClient;
    private final ApprovalMessageCodec codec;
    private final ApprovalMessageProcessor processor;
    private final String queueUrl;
    private final int waitTimeSeconds;
    private final int maxMessages;

    public ApprovalQueueWorker(
            SqsClient sqsClient,
            ApprovalMessageCodec codec,
            ApprovalMessageProcessor processor,
            AwsProperties properties) {
        this.sqsClient = sqsClient;
        this.codec = codec;
        this.processor = processor;
        this.queueUrl = properties.getSqs().getQueueUrl();
        this.waitTimeSeconds = properties.getSqs().getWaitTimeSeconds();
        this.maxMessages = properties.getSqs().getMaxMessages();
        if (!StringUtils.hasText(queueUrl)) {
            throw new IllegalStateException("agentops.aws.sqs.queue-url must be configured when AWS is enabled");
        }
        if (waitTimeSeconds < 0 || waitTimeSeconds > 20) {
            throw new IllegalStateException("agentops.aws.sqs.wait-time-seconds must be between 0 and 20");
        }
        if (maxMessages < 1 || maxMessages > 10) {
            throw new IllegalStateException("agentops.aws.sqs.max-messages must be between 1 and 10");
        }
    }

    @Scheduled(fixedDelayString = "${agentops.aws.sqs.poll-interval:PT1S}")
    public int poll() {
        ReceiveMessageResponse response;
        try {
            response = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .waitTimeSeconds(waitTimeSeconds)
                    .maxNumberOfMessages(maxMessages)
                    .build());
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "event=approval_queue_receive_failed error_type={}",
                    exception.getClass().getSimpleName());
            return 0;
        }

        int processedCount = 0;
        for (Message message : response.messages()) {
            if (processAndDelete(message)) {
                processedCount++;
            }
        }
        return processedCount;
    }

    private boolean processAndDelete(Message message) {
        try {
            processor.process(codec.decode(message.body()));
            sqsClient.deleteMessage(DeleteMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .receiptHandle(message.receiptHandle())
                    .build());
            return true;
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "event=approval_queue_message_failed message_id={} error_type={}",
                    message.messageId(),
                    exception.getClass().getSimpleName());
            return false;
        }
    }
}
