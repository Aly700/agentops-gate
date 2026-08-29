package dev.affan.agentopsgate.domain;

import java.util.Optional;
import java.util.UUID;

public interface DecisionStore {

    Decision storeDecision(Decision decision);

    Optional<Decision> findDecisionById(UUID id);
}
