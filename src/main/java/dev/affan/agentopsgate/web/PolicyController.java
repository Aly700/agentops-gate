package dev.affan.agentopsgate.web;

import dev.affan.agentopsgate.domain.CreatePolicyCommand;
import dev.affan.agentopsgate.domain.CreateRuleCommand;
import dev.affan.agentopsgate.domain.Effect;
import dev.affan.agentopsgate.domain.Policy;
import dev.affan.agentopsgate.domain.PolicyService;
import dev.affan.agentopsgate.domain.RiskTier;
import dev.affan.agentopsgate.domain.Rule;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/policies")
public class PolicyController {

    private final PolicyService policyService;

    public PolicyController(PolicyService policyService) {
        this.policyService = policyService;
    }

    @PostMapping
    ResponseEntity<PolicyResponse> create(@Valid @RequestBody CreatePolicyRequest request) {
        Policy policy = policyService.createPolicy(new CreatePolicyCommand(request.name(), request.version()));
        return ResponseEntity.created(URI.create("/policies/" + policy.getId()))
                .body(PolicyResponse.from(policy));
    }

    @PostMapping("/{policyId}/rules")
    ResponseEntity<RuleResponse> addRule(
            @PathVariable UUID policyId,
            @Valid @RequestBody CreateRuleRequest request) {
        Rule rule = policyService.addRule(policyId, request.toCommand());
        return ResponseEntity.created(URI.create("/policies/" + policyId + "/rules/" + rule.getId()))
                .body(RuleResponse.from(rule));
    }

    public record CreatePolicyRequest(
            @NotBlank @Size(max = 160) String name,
            @Min(1) int version) {
    }

    public record PolicyResponse(UUID id, String name, int version, Instant createdAt) {
        static PolicyResponse from(Policy policy) {
            return new PolicyResponse(
                    policy.getId(),
                    policy.getName(),
                    policy.getVersion(),
                    policy.getCreatedAt());
        }
    }

    public record CreateRuleRequest(
            @NotBlank @Size(max = 255) String toolNameGlob,
            @Size(max = 2_000) String argumentRegex,
            @Size(max = 160) String agentId,
            RiskTier riskTier,
            @NotNull Effect effect,
            @PositiveOrZero int precedence) {

        CreateRuleCommand toCommand() {
            return new CreateRuleCommand(
                    toolNameGlob,
                    argumentRegex,
                    agentId,
                    riskTier,
                    effect,
                    precedence);
        }
    }

    public record RuleResponse(
            UUID id,
            UUID policyId,
            String toolNameGlob,
            String argumentRegex,
            String agentId,
            RiskTier riskTier,
            Effect effect,
            int precedence,
            Instant createdAt) {

        static RuleResponse from(Rule rule) {
            return new RuleResponse(
                    rule.getId(),
                    rule.getPolicyId(),
                    rule.getToolNameGlob(),
                    rule.getArgumentRegex(),
                    rule.getAgentId(),
                    rule.getRiskTier(),
                    rule.getEffect(),
                    rule.getPrecedence(),
                    rule.getCreatedAt());
        }
    }
}
