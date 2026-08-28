package dev.affan.agentopsgate.rules;

import dev.affan.agentopsgate.domain.Effect;
import dev.affan.agentopsgate.domain.RiskTier;
import java.util.Objects;

public record RuleDefinition(
        java.util.UUID id,
        String toolNameGlob,
        String argumentRegex,
        String agentId,
        RiskTier riskTier,
        Effect effect,
        int precedence) {

    public RuleDefinition {
        Objects.requireNonNull(id, "id");
        if (toolNameGlob == null || toolNameGlob.isBlank()) {
            throw new IllegalArgumentException("toolNameGlob must not be blank");
        }
        Objects.requireNonNull(effect, "effect");
        if (precedence < 0) {
            throw new IllegalArgumentException("precedence must not be negative");
        }
    }
}
