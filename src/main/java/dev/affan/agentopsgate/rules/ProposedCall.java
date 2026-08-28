package dev.affan.agentopsgate.rules;

import dev.affan.agentopsgate.domain.RiskTier;
import java.util.Objects;

public record ProposedCall(
        String agentId,
        String toolName,
        String argumentsJson,
        RiskTier riskTier) {

    public ProposedCall {
        requireText(agentId, "agentId");
        requireText(toolName, "toolName");
        Objects.requireNonNull(argumentsJson, "argumentsJson");
        Objects.requireNonNull(riskTier, "riskTier");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
