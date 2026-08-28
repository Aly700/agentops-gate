package dev.affan.agentopsgate.domain;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PolicyService {

    private final PolicyRepository policies;
    private final RuleRepository rules;
    private final AuditService auditService;
    private final Clock clock;

    public PolicyService(
            PolicyRepository policies,
            RuleRepository rules,
            AuditService auditService,
            Clock clock) {
        this.policies = policies;
        this.rules = rules;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    public Policy createPolicy(CreatePolicyCommand command) {
        String name = command.name().trim();
        if (policies.existsByNameAndVersion(name, command.version())) {
            throw new ConflictException("policy name and version already exist");
        }
        Policy policy = policies.save(Policy.create(
                UUID.randomUUID(), name, command.version(), clock.instant()));
        auditService.append(
                AuditEventType.POLICY_CREATED,
                "POLICY",
                policy.getId(),
                Map.of("name", policy.getName(), "version", policy.getVersion()));
        return policy;
    }

    @Transactional
    public Rule addRule(UUID policyId, CreateRuleCommand command) {
        policies.findById(policyId)
                .orElseThrow(() -> new ResourceNotFoundException("policy", policyId));
        validateRegex(command.argumentRegex());
        Rule rule = rules.save(Rule.create(
                UUID.randomUUID(),
                policyId,
                command.toolNameGlob(),
                emptyToNull(command.argumentRegex()),
                emptyToNull(command.agentId()),
                command.riskTier(),
                command.effect(),
                command.precedence(),
                clock.instant()));
        auditService.append(
                AuditEventType.RULE_CREATED,
                "RULE",
                rule.getId(),
                Map.of(
                        "policyId", policyId,
                        "effect", rule.getEffect(),
                        "precedence", rule.getPrecedence()));
        return rule;
    }

    private static void validateRegex(String regex) {
        if (regex == null || regex.isBlank()) {
            return;
        }
        try {
            Pattern.compile(regex);
        } catch (PatternSyntaxException exception) {
            throw new IllegalArgumentException("argumentRegex is invalid", exception);
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
