package dev.affan.agentopsgate.sqs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import dev.affan.agentopsgate.config.AwsProperties;
import dev.affan.agentopsgate.domain.AuditEventType;
import dev.affan.agentopsgate.domain.AuditService;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

class DlqReplayTest {

    @Test
    void movesDlqMessagesToTheMainQueueAndAuditsEachReplay() {
        AtomicReference<ReceiveMessageRequest> receive = new AtomicReference<>();
        List<SendMessageRequest> sends = new ArrayList<>();
        List<DeleteMessageRequest> deletes = new ArrayList<>();
        SqsClient sqsClient = sqsClient(receive, sends, deletes);
        AwsProperties properties = new AwsProperties();
        properties.getSqs().setQueueUrl("https://sqs.test/approvals");
        properties.getSqs().setDlqUrl("https://sqs.test/approvals-dlq");
        AuditService audit = mock(AuditService.class);
        DlqReplayService replay = new DlqReplayService(sqsClient, properties, audit);

        int replayed = replay.replay(5);

        assertThat(replayed).isEqualTo(1);
        assertThat(receive.get().queueUrl()).isEqualTo("https://sqs.test/approvals-dlq");
        assertThat(receive.get().maxNumberOfMessages()).isEqualTo(5);
        assertThat(sends).singleElement().satisfies(request -> {
            assertThat(request.queueUrl()).isEqualTo("https://sqs.test/approvals");
            assertThat(request.messageBody()).isEqualTo("{\"approvalId\":\"value\"}");
        });
        assertThat(deletes).singleElement().satisfies(request -> {
            assertThat(request.queueUrl()).isEqualTo("https://sqs.test/approvals-dlq");
            assertThat(request.receiptHandle()).isEqualTo("receipt-1");
        });
        verify(audit).append(
                eq(AuditEventType.DLQ_REPLAYED),
                eq("DLQ_REPLAY"),
                any(UUID.class),
                argThat((Map<String, ?> details) -> details.get("messageId").equals("message-1")));
    }

    private static SqsClient sqsClient(
            AtomicReference<ReceiveMessageRequest> receive,
            List<SendMessageRequest> sends,
            List<DeleteMessageRequest> deletes) {
        return (SqsClient) Proxy.newProxyInstance(
                SqsClient.class.getClassLoader(),
                new Class<?>[] {SqsClient.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "receiveMessage" -> {
                        receive.set((ReceiveMessageRequest) arguments[0]);
                        yield ReceiveMessageResponse.builder()
                                .messages(Message.builder()
                                        .messageId("message-1")
                                        .receiptHandle("receipt-1")
                                        .body("{\"approvalId\":\"value\"}")
                                        .build())
                                .build();
                    }
                    case "sendMessage" -> {
                        sends.add((SendMessageRequest) arguments[0]);
                        yield SendMessageResponse.builder().messageId("new-message-1").build();
                    }
                    case "deleteMessage" -> {
                        deletes.add((DeleteMessageRequest) arguments[0]);
                        yield DeleteMessageResponse.builder().build();
                    }
                    case "serviceName" -> "sqs";
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
