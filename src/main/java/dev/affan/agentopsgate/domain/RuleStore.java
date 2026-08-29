package dev.affan.agentopsgate.domain;

import java.util.List;
import java.util.UUID;

public interface RuleStore {

    List<Rule> findRulesByPolicyId(UUID policyId);
}
