package dev.affan.agentopsgate.sqs;

import static org.assertj.core.api.Assertions.assertThat;

import dev.affan.agentopsgate.config.AwsProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import tools.jackson.databind.ObjectMapper;

class ApprovalQueueWorkerTest {

    private final ApprovalMessageCodec codec = new ApprovalMessageCodec(new ObjectMapper());

    @Test
    void longPollsProcessesAndDeletesSuccessfulMessages() {
        ApprovalMessage expected = message();
        AtomicReference<ReceiveMessageRequest> receivedRequest = new AtomicReference<>();
        List<DeleteMessageRequest> deletes = new ArrayList<>();
        SqsClient sqsClient = sqsClient(
                List.of(sqsMessage("message-1", "receipt-1", codec.encode(expected))),
                receivedRequest,
                deletes);
        AtomicReference<ApprovalMessage> processed = new AtomicReference<>();
        ApprovalQueueWorker worker = new ApprovalQueueWorker(
                sqsClient, codec, processed::set, properties());

        int processedCount = worker.poll();

        assertThat(processedCount).isEqualTo(1);
        assertThat(processed).hasValue(expected);
        assertThat(receivedRequest.get().queueUrl()).isEqualTo("https://sqs.test/approvals");
        assertThat(receivedRequest.get().waitTimeSeconds()).isEqualTo(20);
        assertThat(receivedRequest.get().maxNumberOfMessages()).isEqualTo(10);
        assertThat(deletes).singleElement().satisfies(request -> {
            assertThat(request.queueUrl()).isEqualTo("https://sqs.test/approvals");
            assertThat(request.receiptHandle()).isEqualTo("receipt-1");
        });
    }

    @Test
    void leavesFailedMessagesOnTheQueueForSqsRedrive() {
        List<DeleteMessageRequest> deletes = new ArrayList<>();
        SqsClient sqsClient = sqsClient(
                List.of(sqsMessage("message-1", "receipt-1", codec.encode(message()))),
                new AtomicReference<>(),
                deletes);
        ApprovalQueueWorker worker = new ApprovalQueueWorker(sqsClient, codec, ignored -> {
            throw new IllegalStateException("transient failure");
        }, properties());

        int processedCount = worker.poll();

        assertThat(processedCount).isZero();
        assertThat(deletes).isEmpty();
    }

    @Test
    void countsProcessedMessagesAndIdempotentDuplicates() {
        ApprovalMessage approvalMessage = message();
        List<DeleteMessageRequest> deletes = new ArrayList<>();
        SqsClient sqsClient = sqsClient(
                List.of(
                        sqsMessage("transport-1", "receipt-1", codec.encode(approvalMessage)),
                        sqsMessage("transport-2", "receipt-2", codec.encode(approvalMessage))),
                new AtomicReference<>(),
                deletes);
        AtomicInteger claims = new AtomicInteger();
        JdbcTemplate jdbcTemplate = new JdbcTemplate() {
            @Override
            public int update(String sql, Object... args) {
                return claims.getAndIncrement() == 0 ? 1 : 0;
            }
        };
        AtomicInteger effects = new AtomicInteger();
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        ApprovalQueueWorker worker = new ApprovalQueueWorker(
                sqsClient,
                codec,
                ignored -> effects.incrementAndGet(),
                jdbcTemplate,
                transactionManager(),
                Clock.fixed(Instant.parse("2026-08-29T12:00:00Z"), ZoneOffset.UTC),
                metrics,
                properties());

        int handled = worker.poll();

        assertThat(handled).isEqualTo(2);
        assertThat(effects).hasValue(1);
        assertThat(deletes).hasSize(2);
        assertThat(metrics.counter("gate.worker.processed").count()).isEqualTo(1.0);
        assertThat(metrics.counter("gate.worker.duplicates").count()).isEqualTo(1.0);
    }

    private static SqsClient sqsClient(
            List<Message> messages,
            AtomicReference<ReceiveMessageRequest> receivedRequest,
            List<DeleteMessageRequest> deletes) {
        return (SqsClient) Proxy.newProxyInstance(
                SqsClient.class.getClassLoader(),
                new Class<?>[] {SqsClient.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "receiveMessage" -> {
                        receivedRequest.set((ReceiveMessageRequest) arguments[0]);
                        yield ReceiveMessageResponse.builder().messages(messages).build();
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

    private static Message sqsMessage(String id, String receiptHandle, String body) {
        return Message.builder()
                .messageId(id)
                .receiptHandle(receiptHandle)
                .body(body)
                .build();
    }

    private static ApprovalMessage message() {
        return new ApprovalMessage(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.parse("2026-08-28T13:00:00Z"));
    }

    private static AwsProperties properties() {
        AwsProperties properties = new AwsProperties();
        properties.getSqs().setQueueUrl("https://sqs.test/approvals");
        return properties;
    }

    private static PlatformTransactionManager transactionManager() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };
    }
}
