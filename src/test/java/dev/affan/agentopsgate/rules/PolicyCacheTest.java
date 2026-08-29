package dev.affan.agentopsgate.rules;

import static org.assertj.core.api.Assertions.assertThat;

import dev.affan.agentopsgate.domain.Effect;
import dev.affan.agentopsgate.domain.Policy;
import dev.affan.agentopsgate.domain.PolicyStore;
import dev.affan.agentopsgate.domain.Rule;
import dev.affan.agentopsgate.domain.RuleStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PolicyCacheTest {

    private static final Instant NOW = Instant.parse("2026-08-29T12:00:00Z");
    private static final UUID POLICY_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");

    @Test
    void repeatedLookupHitsTheCache() {
        AtomicInteger policyLoads = new AtomicInteger();
        AtomicInteger ruleLoads = new AtomicInteger();
        PolicyCache cache = cache(policyLoads, ruleLoads, new ArrayList<>(List.of(rule(10, Effect.ALLOW))));

        PolicyCache.PolicyRules first = cache.get(POLICY_ID);
        PolicyCache.PolicyRules second = cache.get(POLICY_ID);

        assertThat(second).isSameAs(first);
        assertThat(policyLoads).hasValue(1);
        assertThat(ruleLoads).hasValue(1);
    }

    @Test
    void invalidationAfterRuleAdditionReloadsTheRuleSet() {
        AtomicInteger policyLoads = new AtomicInteger();
        AtomicInteger ruleLoads = new AtomicInteger();
        List<Rule> rules = new ArrayList<>(List.of(rule(10, Effect.ALLOW)));
        PolicyCache cache = cache(policyLoads, ruleLoads, rules);
        PolicyCache.PolicyRules before = cache.get(POLICY_ID);

        rules.add(rule(5, Effect.DENY));
        cache.invalidate(POLICY_ID);
        PolicyCache.PolicyRules after = cache.get(POLICY_ID);

        assertThat(after).isNotSameAs(before);
        assertThat(after.rules()).extracting(Rule::getEffect).containsExactly(Effect.DENY, Effect.ALLOW);
        assertThat(policyLoads).hasValue(2);
        assertThat(ruleLoads).hasValue(2);
    }

    private static PolicyCache cache(
            AtomicInteger policyLoads,
            AtomicInteger ruleLoads,
            List<Rule> rules) {
        Policy policy = Policy.create(POLICY_ID, "cached", 1, NOW);
        PolicyStore policies = id -> {
            policyLoads.incrementAndGet();
            return Optional.of(policy);
        };
        RuleStore ruleStore = id -> {
            ruleLoads.incrementAndGet();
            return rules.stream().sorted(java.util.Comparator.comparingInt(Rule::getPrecedence)).toList();
        };
        return new PolicyCache(policies, ruleStore);
    }

    private static Rule rule(int precedence, Effect effect) {
        return Rule.create(
                UUID.randomUUID(),
                POLICY_ID,
                "*",
                null,
                null,
                null,
                effect,
                precedence,
                NOW);
    }
}
