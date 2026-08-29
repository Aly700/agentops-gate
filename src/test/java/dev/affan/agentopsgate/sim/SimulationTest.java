package dev.affan.agentopsgate.sim;

import dev.affan.agentopsgate.config.AwsProperties;
import dev.affan.agentopsgate.domain.Approval;
import dev.affan.agentopsgate.domain.ApprovalService;
import dev.affan.agentopsgate.domain.AuditService;
import dev.affan.agentopsgate.domain.DecisionService;
import dev.affan.agentopsgate.domain.Effect;
import dev.affan.agentopsgate.domain.EvaluateDecisionCommand;
import dev.affan.agentopsgate.domain.Policy;
import dev.affan.agentopsgate.domain.RiskTier;
import dev.affan.agentopsgate.domain.Rule;
import dev.affan.agentopsgate.rules.RulesEngine;
import dev.affan.agentopsgate.sqs.ApprovalExpiryWorker;
import dev.affan.agentopsgate.sqs.ApprovalMessageCodec;
import dev.affan.agentopsgate.sqs.ApprovalMessageValidator;
import dev.affan.agentopsgate.sqs.ApprovalQueueWorker;
import dev.affan.agentopsgate.sqs.OutboxRelay;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SimulationTest {

    private static final Instant INITIAL_TIME = Instant.parse("2026-08-29T00:00:00Z");
    private static final int DEFAULT_SEEDS = 200;

    @Test
    void seededFaultsPreserveProductionInvariants() {
        long started = System.nanoTime();
        String exactSeed = System.getProperty("sim.seed");
        int seedsRun;
        if (exactSeed != null) {
            runSeed(Long.parseLong(exactSeed));
            seedsRun = 1;
        } else {
            int seedCount = Integer.getInteger("sim.seeds", DEFAULT_SEEDS);
            if (seedCount < 1) {
                throw new IllegalArgumentException("sim.seeds must be positive");
            }
            for (long seed = 1; seed <= seedCount; seed++) {
                runSeed(seed);
            }
            seedsRun = seedCount;
        }
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        System.out.printf("simulation seeds=%d elapsed_ms=%d%n", seedsRun, elapsedMillis);
    }

    private static void runSeed(long seed) {
        Trace trace = new Trace(seed);
        try {
            Simulator simulator = new Simulator(seed, INITIAL_TIME, trace);
            InMemoryStores stores = new InMemoryStores(trace, simulator::instant);
            ObjectMapper objectMapper = new ObjectMapper();
            ApprovalMessageCodec codec = new ApprovalMessageCodec(objectMapper);
            FaultInjectingBus bus = FaultInjectingBus.standard(simulator, trace, codec);
            Invariants invariants = new Invariants(stores, bus);

            AuditService auditService = new AuditService(stores.auditStore(), objectMapper, simulator);
            ApprovalService approvalService = new ApprovalService(
                    stores.approvalStore(),
                    auditService,
                    simulator,
                    new ApprovalMessageValidator());
            DecisionService decisionService = new DecisionService(
                    stores.policyStore(),
                    stores.ruleStore(),
                    stores.decisionStore(),
                    stores.approvalStore(),
                    new RulesEngine(),
                    stores.outboxStore(),
                    codec,
                    auditService,
                    simulator,
                    Duration.ofSeconds(2));
            OutboxRelay relay = new OutboxRelay(
                    stores.outboxStore(),
                    bus,
                    codec,
                    new SimpleMeterRegistry(),
                    simulator);
            ApprovalQueueWorker queueWorker = new ApprovalQueueWorker(
                    bus.sqsClient(),
                    codec,
                    bus.processor(approvalService),
                    stores.processedMessagesJdbc(simulator::instant),
                    stores.transactionManager(),
                    simulator,
                    workerProperties());
            ApprovalExpiryWorker expiryWorker = new ApprovalExpiryWorker(approvalService);

            Policy policy = policy(seed, simulator.instant());
            stores.seedPolicy(policy);
            stores.seedRule(requireApprovalRule(seed, policy.getId(), simulator.instant()));

            List<EvaluateDecisionCommand> commands = commands(policy.getId());
            for (int index = 0; index < commands.size(); index++) {
                String key = "seed-" + seed + "-request-" + index;
                EvaluateDecisionCommand command = commands.get(index);
                simulator.schedule(
                        Duration.ofMillis(index * 20L),
                        "decision-attempt key=" + key,
                        () -> submitWithFaults(key, command, decisionService, stores, bus, simulator));
                simulator.schedule(
                        Duration.ofMillis(100L + index * 20L),
                        "decision-retry key=" + key,
                        () -> stores.evaluateIdempotently(
                                key, () -> decisionService.evaluate(command), simulator.instant()));
                simulator.schedule(
                        Duration.ofMillis(200L + index * 20L),
                        "decision-idempotent-replay key=" + key,
                        () -> stores.evaluateIdempotently(
                                key, () -> decisionService.evaluate(command), simulator.instant()));
            }

            for (int index = 0; index < 45; index++) {
                simulator.schedule(
                        Duration.ofMillis(300L + index * 100L),
                        "outbox-relay",
                        relay::relayOnce);
                simulator.schedule(
                        Duration.ofMillis(350L + index * 100L),
                        "queue-poll",
                        queueWorker::poll);
            }
            simulator.schedule(Duration.ofMillis(800), "human-decisions", () ->
                    decideSomeApprovals(seed, stores, approvalService, simulator));
            simulator.schedule(Duration.ofSeconds(4), "expiry-sweep", expiryWorker::expireStaleApprovals);

            simulator.schedule(Duration.ofSeconds(5), "healthy-period", bus::recover);
            simulator.schedule(Duration.ofMillis(5_010), "final-outbox-relay", relay::relayOnce);
            for (int index = 0; index < 12; index++) {
                simulator.schedule(
                        Duration.ofMillis(5_100L + index * 100L),
                        "final-queue-poll",
                        queueWorker::poll);
            }
            simulator.schedule(Duration.ofMillis(5_500), "final-expiry-sweep", expiryWorker::expireStaleApprovals);

            simulator.runUntilIdle(5_000, invariants::checkAfterStep);
            invariants.checkAfterQuiescence();
        } catch (Throwable failure) {
            throw trace.failure(failure);
        }
    }

    private static void submitWithFaults(
            String key,
            EvaluateDecisionCommand command,
            DecisionService decisionService,
            InMemoryStores stores,
            FaultInjectingBus bus,
            Simulator simulator) {
        if (bus.crashBeforeCommit("decision")) {
            return;
        }
        stores.evaluateIdempotently(key, () -> decisionService.evaluate(command), simulator.instant());
        bus.crashAfterCommit("decision");
    }

    private static void decideSomeApprovals(
            long seed,
            InMemoryStores stores,
            ApprovalService approvalService,
            Simulator simulator) {
        List<Approval> approvals = new ArrayList<>(stores.approvals());
        for (int index = 0; index < approvals.size(); index++) {
            Approval approval = approvals.get(index);
            int outcome = Math.floorMod((int) seed + index, 3);
            if (outcome == 0) {
                approvalService.approve(approval.getId(), "sim-reviewer");
            } else if (outcome == 1) {
                approvalService.deny(approval.getId(), "sim-reviewer");
            } else {
                simulator.schedule(Duration.ZERO, "approval-left-for-expiry " + approval.getId(), () -> {
                });
            }
        }
    }

    private static Policy policy(long seed, Instant now) {
        return Policy.create(stableUuid(seed, "policy"), "simulation-" + seed, 1, now);
    }

    private static Rule requireApprovalRule(long seed, UUID policyId, Instant now) {
        return Rule.create(
                stableUuid(seed, "rule"),
                policyId,
                "fs.*",
                null,
                null,
                null,
                Effect.REQUIRE_APPROVAL,
                10,
                now);
    }

    private static List<EvaluateDecisionCommand> commands(UUID policyId) {
        return List.of(
                new EvaluateDecisionCommand(policyId, "agent-a", "fs.read", "{\"path\":\"/a\"}", RiskTier.LOW),
                new EvaluateDecisionCommand(policyId, "agent-b", "fs.write", "{\"path\":\"/b\"}", RiskTier.HIGH),
                new EvaluateDecisionCommand(policyId, "agent-c", "fs.delete", "{\"path\":\"/c\"}", RiskTier.CRITICAL));
    }

    private static UUID stableUuid(long seed, String label) {
        return UUID.nameUUIDFromBytes((seed + ":" + label).getBytes(StandardCharsets.UTF_8));
    }

    private static AwsProperties workerProperties() {
        AwsProperties properties = new AwsProperties();
        properties.getSqs().setQueueUrl("memory://approval-queue");
        properties.getSqs().setWaitTimeSeconds(0);
        properties.getSqs().setMaxMessages(10);
        return properties;
    }
}
