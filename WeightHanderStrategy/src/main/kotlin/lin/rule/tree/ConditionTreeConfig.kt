package lin.rule.tree

data class ConditionTreeConfig(
    val bindIds: List<Double>,
    val root: ConditionNode,
    val ruleConfigs: Map<String, RuleConfig>
)

data class RuleConfig(
    val nodeId: String,
    val ruleId: String,
    val depByWeightIds: List<Double> = emptyList(),
    val args: Map<String, Any> = emptyMap()
)
