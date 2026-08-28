package dev.affan.agentopsgate.domain;

import dev.affan.agentopsgate.rules.RuleDefinition;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "rules")
public class Rule {

    @Id
    private UUID id;

    @Column(name = "policy_id", nullable = false, updatable = false)
    private UUID policyId;

    @Column(name = "tool_name_glob", nullable = false, updatable = false)
    private String toolNameGlob;

    @Column(name = "argument_regex", updatable = false)
    private String argumentRegex;

    @Column(name = "agent_id", updatable = false)
    private String agentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_tier", updatable = false)
    private RiskTier riskTier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Effect effect;

    @Column(nullable = false, updatable = false)
    private int precedence;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Rule() {
    }

    private Rule(
            UUID id,
            UUID policyId,
            String toolNameGlob,
            String argumentRegex,
            String agentId,
            RiskTier riskTier,
            Effect effect,
            int precedence,
            Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.policyId = Objects.requireNonNull(policyId, "policyId");
        this.toolNameGlob = Objects.requireNonNull(toolNameGlob, "toolNameGlob");
        this.argumentRegex = argumentRegex;
        this.agentId = agentId;
        this.riskTier = riskTier;
        this.effect = Objects.requireNonNull(effect, "effect");
        this.precedence = precedence;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public static Rule create(
            UUID id,
            UUID policyId,
            String toolNameGlob,
            String argumentRegex,
            String agentId,
            RiskTier riskTier,
            Effect effect,
            int precedence,
            Instant createdAt) {
        return new Rule(
                id,
                policyId,
                toolNameGlob,
                argumentRegex,
                agentId,
                riskTier,
                effect,
                precedence,
                createdAt);
    }

    public RuleDefinition toDefinition() {
        return new RuleDefinition(id, toolNameGlob, argumentRegex, agentId, riskTier, effect, precedence);
    }

    public UUID getId() {
        return id;
    }

    public UUID getPolicyId() {
        return policyId;
    }

    public String getToolNameGlob() {
        return toolNameGlob;
    }

    public String getArgumentRegex() {
        return argumentRegex;
    }

    public String getAgentId() {
        return agentId;
    }

    public RiskTier getRiskTier() {
        return riskTier;
    }

    public Effect getEffect() {
        return effect;
    }

    public int getPrecedence() {
        return precedence;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
