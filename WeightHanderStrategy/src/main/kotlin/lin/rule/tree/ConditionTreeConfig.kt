package lin.rule.tree

data class ConditionTreeConfig(
    val bindIds: List<Double>,
    val root: ConditionNode,
    val ruleConfigs: Map<String, RuleConfig>
)

data class RuleConfig(
    val nodeId: String,
    val ruleId: String,
    val weight: Double,
    val mismatchedWeight: Double,
    val args: Map<String, Any> = emptyMap()
)
