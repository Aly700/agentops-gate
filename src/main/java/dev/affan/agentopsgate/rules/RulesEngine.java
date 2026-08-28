package dev.affan.agentopsgate.rules;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public final class RulesEngine {

    public RuleEvaluation evaluate(List<RuleDefinition> rules, ProposedCall call) {
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(call, "call");

        return rules.stream()
                .sorted(Comparator.comparingInt(RuleDefinition::precedence))
                .filter(rule -> matches(rule, call))
                .findFirst()
                .map(RuleEvaluation::matched)
                .orElseGet(RuleEvaluation::defaultDeny);
    }

    private boolean matches(RuleDefinition rule, ProposedCall call) {
        return toolMatches(rule.toolNameGlob(), call.toolName())
                && optionalRegexMatches(rule.argumentRegex(), call.argumentsJson())
                && optionalValueMatches(rule.agentId(), call.agentId())
                && optionalValueMatches(rule.riskTier(), call.riskTier());
    }

    private boolean toolMatches(String glob, String toolName) {
        String[] literalSegments = glob.split("\\*", -1);
        StringBuilder regex = new StringBuilder("^");
        for (int index = 0; index < literalSegments.length; index++) {
            if (index > 0) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(literalSegments[index]));
        }
        regex.append('$');
        return Pattern.compile(regex.toString(), Pattern.DOTALL).matcher(toolName).matches();
    }

    private boolean optionalRegexMatches(String regex, String value) {
        return regex == null || Pattern.compile(regex, Pattern.DOTALL).matcher(value).find();
    }

    private boolean optionalValueMatches(Object matcher, Object value) {
        return matcher == null || matcher.equals(value);
    }
}
