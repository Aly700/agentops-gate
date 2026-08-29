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
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.PurgeQueueRequest;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "agentops.api-key=integration-key",
        "agentops.aws.enabled=true",
        "agentops.aws.sqs.worker-enabled=true",
        "agentops.aws.sqs.wait-time-seconds=1",
        "agentops.aws.sqs.poll-interval=PT0.1S",
        "agentops.outbox.relay-initial-delay=PT1H",
        "agentops.approval.ttl=PT0.15S",
        "agentops.approval.expiry-interval=PT1H"
})
class SqsWorkerIntegrationTest extends LocalStackIntegrationTest {

    @Autowired private PolicyService policies;
    @Autowired private DecisionService decisions;
    @Autowired private ApprovalQueuePublisher publisher;
    @Autowired private ApprovalRepository approvals;
    @Autowired private AuditRecordRepository auditRecords;
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
    void workerExpiresAStaleRedeliveryWithoutDoubleApplyingIt() throws InterruptedException {
        Policy policy = policies.createPolicy(new CreatePolicyCommand("worker-" + UUID.randomUUID(), 1));
        policies.addRule(policy.getId(), new CreateRuleCommand(
                "fs.*", null, null, RiskTier.HIGH, Effect.REQUIRE_APPROVAL, 10));

        DecisionOutcome outcome = decisions.evaluate(new EvaluateDecisionCommand(
                policy.getId(), "agent-1", "fs.write", "{\"path\":\"/sandbox/report.txt\"}", RiskTier.HIGH));

        assertThat(outcome.approval()).isNotNull();
        Thread.sleep(Duration.ofMillis(200));
        ApprovalMessage duplicate = new ApprovalMessage(
                UUID.randomUUID(),
                outcome.approval().getId(),
                outcome.decision().getId(),
                outcome.approval().getExpiresAt());
        publisher.publish(duplicate);
        publisher.publish(duplicate);

        awaitApprovalIsExpired(outcome.approval().getId(), Duration.ofSeconds(15));
        assertThat(auditRecords.findAll())
                .filteredOn(record -> record.getAggregateId().equals(outcome.approval().getId()))
                .filteredOn(record -> record.getEventType() == AuditEventType.APPROVAL_EXPIRED)
                .hasSize(1);
    }

    private void awaitApprovalIsExpired(UUID approvalId, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        do {
            if (approvals.findById(approvalId).orElseThrow().getStatus() == ApprovalStatus.EXPIRED) {
                return;
            }
            Thread.sleep(100);
        } while (Instant.now().isBefore(deadline));
        assertThat(approvals.findById(approvalId).orElseThrow().getStatus())
                .as("the scheduled worker expires the stale approval")
                .isEqualTo(ApprovalStatus.EXPIRED);
    }
}
