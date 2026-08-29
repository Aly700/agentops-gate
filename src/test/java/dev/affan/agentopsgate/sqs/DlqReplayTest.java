package dev.affan.agentopsgate.sqs;

import static org.assertj.core.api.Assertions.assertThat;

import dev.affan.agentopsgate.config.AwsProperties;
import dev.affan.agentopsgate.domain.AuditEventType;
import dev.affan.agentopsgate.domain.AuditRecord;
import dev.affan.agentopsgate.domain.AuditRecordRepository;
import dev.affan.agentopsgate.domain.AuditService;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import tools.jackson.databind.ObjectMapper;

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
        AtomicReference<AuditRecord> auditRecord = new AtomicReference<>();
        AuditService audit = auditService(auditRecord);
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
        assertThat(auditRecord.get().getEventType()).isEqualTo(AuditEventType.DLQ_REPLAYED);
        assertThat(auditRecord.get().getAggregateType()).isEqualTo("DLQ_REPLAY");
        assertThat(new ObjectMapper().readTree(auditRecord.get().getDetails()).get("messageId").asString())
                .isEqualTo("message-1");
    }

    private static AuditService auditService(AtomicReference<AuditRecord> saved) {
        AuditRecordRepository repository = (AuditRecordRepository) Proxy.newProxyInstance(
                AuditRecordRepository.class.getClassLoader(),
                new Class<?>[] {AuditRecordRepository.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("save")) {
                        AuditRecord record = (AuditRecord) arguments[0];
                        saved.set(record);
                        return record;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        return new AuditService(
                repository,
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-08-29T12:00:00Z"), ZoneOffset.UTC));
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
