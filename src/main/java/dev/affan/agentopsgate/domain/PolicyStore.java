package dev.affan.agentopsgate.domain;

import java.util.Optional;
import java.util.UUID;

public interface PolicyStore {

    Optional<Policy> findPolicyById(UUID id);
}
