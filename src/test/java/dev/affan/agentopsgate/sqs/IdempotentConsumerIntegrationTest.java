package dev.affan.agentopsgate.sqs;

import static org.assertj.core.api.Assertions.assertThat;

import dev.affan.agentopsgate.LocalStackIntegrationTest;
import dev.affan.agentopsgate.TestcontainersConfiguration;
import dev.affan.agentopsgate.domain.ApprovalRepository;
import dev.affan.agentopsgate.domain.ApprovalStatus;
import dev.affan.agentopsgate.domain.AuditEventType;
import dev.affan.agentopsgate.domain.AuditRecordRepository;
import dev.affan.agentopsgate.domain.CreatePolicyCommand;
import dev.affan.agentopsgate.domain.CreateRuleCommand;
import dev.affan.agentopsgate.domain.DecisionOutcome;
import dev.affan.agentopsgate.domain.DecisionService;
import dev.affan.agentopsgate.domain.Effect;
import dev.affan.agentopsgate.domain.EvaluateDecisionCommand;
import dev.affan.agentopsgate.domain.Policy;
import dev.affan.agentopsgate.domain.PolicyService;
import dev.affan.agentopsgate.domain.RiskTier;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.PurgeQueueRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "agentops.api-key=integration-key",
        "agentops.aws.enabled=true",
        "agentops.aws.sqs.worker-enabled=true",
        "agentops.aws.sqs.wait-time-seconds=0",
        "agentops.aws.sqs.worker-initial-delay=PT1H",
        "agentops.outbox.relay-initial-delay=PT1H",
        "agentops.approval.ttl=PT0.1S",
        "agentops.approval.expiry-interval=PT1H"
})
class IdempotentConsumerIntegrationTest extends LocalStackIntegrationTest {

    @Autowired private PolicyService policies;
    @Autowired private DecisionService decisions;
    @Autowired private OutboxRelay relay;
    @Autowired private ApprovalQueueWorker worker;
    @Autowired private ApprovalRepository approvals;
    @Autowired private AuditRecordRepository auditRecords;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private SqsClient sqsClient;

    @BeforeEach
    void createsAndClearsQueue() {
        String queueUrl = sqsClient.createQueue(CreateQueueRequest.builder()
                        .queueName(QUEUE_NAME)
                        .build())
                .queueUrl();
        sqsClient.purgeQueue(PurgeQueueRequest.builder().queueUrl(queueUrl).build());
    }

    @Test
    void duplicateMessageIdProducesOneStateChangeAndOneAuditRecord() throws Exception {
        Policy policy = policies.createPolicy(new CreatePolicyCommand("consumer-" + UUID.randomUUID(), 1));
        policies.addRule(policy.getId(), new CreateRuleCommand(
                "fs.*", null, null, RiskTier.HIGH, Effect.REQUIRE_APPROVAL, 10));
        DecisionOutcome outcome = decisions.evaluate(new EvaluateDecisionCommand(
                policy.getId(), "agent-1", "fs.write", "{}", RiskTier.HIGH));
        relay.relayOnce();
        Message message = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                        .queueUrl(LOCALSTACK.getEndpoint() + "/000000000000/" + QUEUE_NAME)
                        .waitTimeSeconds(5)
                        .maxNumberOfMessages(1)
                        .build())
                .messages().getFirst();
        Thread.sleep(Duration.ofMillis(200));

        worker.processAndDelete(message);
        worker.processAndDelete(message);

        assertThat(approvals.findById(outcome.approval().getId()).orElseThrow().getStatus())
                .isEqualTo(ApprovalStatus.EXPIRED);
        assertThat(auditRecords.findAll())
                .filteredOn(record -> record.getAggregateId().equals(outcome.approval().getId()))
                .filteredOn(record -> record.getEventType() == AuditEventType.APPROVAL_EXPIRED)
                .hasSize(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM processed_messages WHERE message_id = ?",
                Integer.class,
                message.messageId())).isEqualTo(1);
    }
}
