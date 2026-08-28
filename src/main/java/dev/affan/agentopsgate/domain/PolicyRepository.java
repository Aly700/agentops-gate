package dev.affan.agentopsgate.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PolicyRepository extends JpaRepository<Policy, UUID> {
    boolean existsByNameAndVersion(String name, int version);
}
