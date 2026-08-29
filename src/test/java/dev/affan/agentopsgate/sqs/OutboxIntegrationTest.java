package dev.affan.agentopsgate.sqs;

import static org.assertj.core.api.Assertions.assertThat;

import dev.affan.agentopsgate.LocalStackIntegrationTest;
import dev.affan.agentopsgate.TestcontainersConfiguration;
import dev.affan.agentopsgate.config.AwsProperties;
import dev.affan.agentopsgate.domain.CreatePolicyCommand;
import dev.affan.agentopsgate.domain.CreateRuleCommand;
import dev.affan.agentopsgate.domain.DecisionService;
import dev.affan.agentopsgate.domain.Effect;
import dev.affan.agentopsgate.domain.EvaluateDecisionCommand;
import dev.affan.agentopsgate.domain.Policy;
import dev.affan.agentopsgate.domain.PolicyService;
import dev.affan.agentopsgate.domain.RiskTier;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.PurgeQueueRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

@Import({TestcontainersConfiguration.class, OutboxIntegrationTest.FailOncePublisherConfiguration.class})
@SpringBootTest(properties = {
        "agentops.api-key=integration-key",
        "agentops.aws.enabled=true",
        "agentops.aws.sqs.worker-enabled=false",
        "agentops.outbox.relay-initial-delay=PT1H"
})
class OutboxIntegrationTest extends LocalStackIntegrationTest {

    @Autowired private PolicyService policies;
    @Autowired private DecisionService decisions;
    @Autowired private OutboxRelay relay;
    @Autowired private OutboxRepository outbox;
    @Autowired private SqsClient sqsClient;
    @Autowired private ApprovalMessageCodec codec;

    @BeforeEach
    void createsAndClearsQueue() {
        String queueUrl = sqsClient.createQueue(CreateQueueRequest.builder()
                        .queueName(QUEUE_NAME)
                        .build())
                .queueUrl();
        sqsClient.purgeQueue(PurgeQueueRequest.builder().queueUrl(queueUrl).build());
    }

    @Test
    void retriesACommittedRowAfterPublisherFailureAndDeliversOnce() {
        Policy policy = policies.createPolicy(new CreatePolicyCommand("outbox-" + UUID.randomUUID(), 1));
        policies.addRule(policy.getId(), new CreateRuleCommand(
                "fs.*", null, null, RiskTier.HIGH, Effect.REQUIRE_APPROVAL, 10));

        decisions.evaluate(new EvaluateDecisionCommand(
                policy.getId(), "agent-1", "fs.write", "{}", RiskTier.HIGH));

        assertThat(outbox.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getSentAt()).isNull();
            assertThat(codec.decode(row.getPayload()).messageId()).isEqualTo(row.getId());
        });
        assertThat(relay.relayOnce()).isZero();
        assertThat(receive()).isEmpty();
        assertThat(relay.relayOnce()).isEqualTo(1);
        assertThat(receive()).hasSize(1);
        assertThat(relay.relayOnce()).isZero();
        assertThat(receive()).isEmpty();
        assertThat(outbox.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getSentAt()).isNotNull();
            assertThat(row.getAttempts()).isEqualTo(2);
        });
    }

    private List<software.amazon.awssdk.services.sqs.model.Message> receive() {
        return sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                        .queueUrl(LOCALSTACK.getEndpoint() + "/000000000000/" + QUEUE_NAME)
                        .waitTimeSeconds(1)
                        .maxNumberOfMessages(10)
                        .build())
                .messages();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FailOncePublisherConfiguration {

        @Bean
        @Primary
        ApprovalQueuePublisher failOncePublisher(
                SqsClient sqsClient,
                ApprovalMessageCodec codec,
                AwsProperties properties) {
            AtomicBoolean fail = new AtomicBoolean(true);
            return message -> {
                if (fail.getAndSet(false)) {
                    throw new IllegalStateException("simulated crash before publish");
                }
                sqsClient.sendMessage(SendMessageRequest.builder()
                        .queueUrl(properties.getSqs().getQueueUrl())
                        .messageBody(codec.encode(message))
                        .build());
            };
        }
    }
}
