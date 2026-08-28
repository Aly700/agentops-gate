package dev.affan.agentopsgate.domain;

public record CreateRuleCommand(
        String toolNameGlob,
        String argumentRegex,
        String agentId,
        RiskTier riskTier,
        Effect effect,
        int precedence) {
}
