package lin.rule.tree

data class EvaluatorTreeConfig(
    val bindGroupIds: List<String>,
    val root: EvaluatorNode,
    val leafConfigs: Map<String, EvaluatorLeafConfig>
)
