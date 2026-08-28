package dev.affan.agentopsgate.sqs;

import dev.affan.agentopsgate.domain.ApprovalService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public final class ApprovalExpiryWorker {

    private final ApprovalService approvalService;

    public ApprovalExpiryWorker(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @Scheduled(fixedDelayString = "${agentops.approval.expiry-interval:PT1M}")
    public int expireStaleApprovals() {
        return approvalService.expireStale();
    }
}
