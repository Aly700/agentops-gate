package dev.affan.agentopsgate.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PolicyRepository extends JpaRepository<Policy, UUID>, PolicyStore {
    boolean existsByNameAndVersion(String name, int version);

    @Override
    default java.util.Optional<Policy> findPolicyById(UUID id) {
        return findById(id);
    }
}
