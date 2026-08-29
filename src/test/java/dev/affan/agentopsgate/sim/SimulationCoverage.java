package dev.affan.agentopsgate.sim;

import dev.affan.agentopsgate.domain.ApprovalStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

final class SimulationCoverage {

    private long seeds;
    private long totalSteps;
    private long decisionsCreated;
    private long duplicatesDelivered;
    private final Map<ApprovalStatus, Long> terminalApprovals = new EnumMap<>(ApprovalStatus.class);
    private final Map<String, Long> faultsInjected = new LinkedHashMap<>();

    SimulationCoverage() {
        terminalApprovals.put(ApprovalStatus.APPROVED, 0L);
        terminalApprovals.put(ApprovalStatus.DENIED, 0L);
        terminalApprovals.put(ApprovalStatus.EXPIRED, 0L);
        faultsInjected.put("duplicate", 0L);
        faultsInjected.put("reorder", 0L);
        faultsInjected.put("delay", 0L);
        faultsInjected.put("drop_then_redeliver", 0L);
        faultsInjected.put("crash_before_commit", 0L);
        faultsInjected.put("crash_after_commit", 0L);
    }

    void record(Simulator simulator, InMemoryStores stores, FaultInjectingBus bus) {
        seeds++;
        totalSteps += simulator.executedSteps();
        decisionsCreated += stores.decisions().size();
        stores.approvals().forEach(approval -> terminalApprovals.computeIfPresent(
                approval.getStatus(),
                (status, count) -> count + 1));
        bus.faultCounts().forEach((kind, count) -> faultsInjected.merge(kind, count, Long::sum));
        duplicatesDelivered += bus.duplicatesDelivered();
    }

    void write(Path output) {
        try {
            Files.createDirectories(output.getParent());
            Files.writeString(output, new ObjectMapper().writeValueAsString(document()));
        } catch (IOException exception) {
            throw new IllegalStateException("could not write simulation coverage to " + output, exception);
        }
    }

    String summary() {
        return "steps=" + totalSteps
                + " decisions_created=" + decisionsCreated
                + " approvals=" + terminalApprovals
                + " faults=" + faultsInjected
                + " duplicates_delivered=" + duplicatesDelivered
                + " crashes_before_commit=" + faultsInjected.get("crash_before_commit")
                + " crashes_after_commit=" + faultsInjected.get("crash_after_commit")
                + " coverage=target/sim-coverage.json";
    }

    private Map<String, Object> document() {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("seeds", seeds);
        document.put("totalSteps", totalSteps);
        document.put("decisionsCreated", decisionsCreated);
        document.put("approvalsByTerminalState", terminalApprovals);
        document.put("faultsInjected", faultsInjected);
        document.put("duplicatesDelivered", duplicatesDelivered);
        document.put("crashesBeforeCommit", faultsInjected.get("crash_before_commit"));
        document.put("crashesAfterCommit", faultsInjected.get("crash_after_commit"));
        return document;
    }
}
