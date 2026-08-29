package dev.affan.agentopsgate.rules;

import dev.affan.agentopsgate.domain.Policy;
import dev.affan.agentopsgate.domain.PolicyStore;
import dev.affan.agentopsgate.domain.ResourceNotFoundException;
import dev.affan.agentopsgate.domain.Rule;
import dev.affan.agentopsgate.domain.RuleStore;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

@Component
public final class PolicyCache {

    private final PolicyStore policies;
    private final RuleStore rules;
    private final ConcurrentMap<CacheKey, PolicyRules> entries = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Long> ruleSetVersions = new ConcurrentHashMap<>();

    public PolicyCache(PolicyStore policies, RuleStore rules) {
        this.policies = policies;
        this.rules = rules;
    }

    public PolicyRules get(UUID policyId) {
        Objects.requireNonNull(policyId, "policyId");
        CacheKey key = new CacheKey(policyId, ruleSetVersions.getOrDefault(policyId, 0L));
        return entries.computeIfAbsent(key, ignored -> load(policyId));
    }

    public void invalidate(UUID policyId) {
        Objects.requireNonNull(policyId, "policyId");
        ruleSetVersions.merge(policyId, 1L, Long::sum);
        entries.keySet().removeIf(key -> key.policyId().equals(policyId));
    }

    private PolicyRules load(UUID policyId) {
        Policy policy = policies.findPolicyById(policyId)
                .orElseThrow(() -> new ResourceNotFoundException("policy", policyId));
        return new PolicyRules(policy, rules.findRulesByPolicyId(policyId));
    }

    private record CacheKey(UUID policyId, long ruleSetVersion) {
    }

    public record PolicyRules(Policy policy, List<Rule> rules) {

        public PolicyRules {
            Objects.requireNonNull(policy, "policy");
            rules = List.copyOf(rules);
        }
    }
}
