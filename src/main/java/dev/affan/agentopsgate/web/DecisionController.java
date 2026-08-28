package dev.affan.agentopsgate.web;

import dev.affan.agentopsgate.domain.Decision;
import dev.affan.agentopsgate.domain.DecisionOutcome;
import dev.affan.agentopsgate.domain.DecisionService;
import dev.affan.agentopsgate.domain.Effect;
import dev.affan.agentopsgate.domain.EvaluateDecisionCommand;
import dev.affan.agentopsgate.domain.RiskTier;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/decisions")
public class DecisionController {

    private final DecisionService decisionService;
    private final ObjectMapper objectMapper;

    public DecisionController(DecisionService decisionService, ObjectMapper objectMapper) {
        this.decisionService = decisionService;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    ResponseEntity<DecisionResponse> evaluate(@Valid @RequestBody EvaluateDecisionRequest request) {
        DecisionOutcome outcome = decisionService.evaluate(request.toCommand());
        DecisionResponse response = DecisionResponse.from(
                outcome.decision(),
                outcome.approval() == null ? null : outcome.approval().getId(),
                objectMapper);
        return ResponseEntity.created(URI.create("/decisions/" + response.id())).body(response);
    }

    @GetMapping("/{id}")
    DecisionResponse get(@PathVariable UUID id) {
        return DecisionResponse.from(decisionService.get(id), null, objectMapper);
    }

    public record EvaluateDecisionRequest(
            @NotNull UUID policyId,
            @NotBlank @Size(max = 160) String agentId,
            @NotBlank @Size(max = 255) String toolName,
            @NotNull JsonNode arguments,
            @NotNull RiskTier riskTier) {

        EvaluateDecisionCommand toCommand() {
            return new EvaluateDecisionCommand(
                    policyId,
                    agentId,
                    toolName,
                    arguments.toString(),
                    riskTier);
        }
    }

    public record DecisionResponse(
            UUID id,
            UUID policyId,
            int policyVersion,
            String agentId,
            String toolName,
            JsonNode arguments,
            RiskTier riskTier,
            UUID matchedRuleId,
            Effect effect,
            Instant timestamp,
            UUID approvalId) {

        static DecisionResponse from(Decision decision, UUID approvalId, ObjectMapper objectMapper) {
            return new DecisionResponse(
                    decision.getId(),
                    decision.getPolicyId(),
                    decision.getPolicyVersion(),
                    decision.getAgentId(),
                    decision.getToolName(),
                    objectMapper.readTree(decision.getArguments()),
                    decision.getRiskTier(),
                    decision.getMatchedRuleId(),
                    decision.getEffect(),
                    decision.getDecidedAt(),
                    approvalId);
        }
    }
}
