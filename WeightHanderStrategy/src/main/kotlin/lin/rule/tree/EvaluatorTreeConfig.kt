package lin.rule.tree

data class EvaluatorTreeConfig(
    val bindings: List<EvaluatorTreeBinding>,
    val root: EvaluatorNode,
    val leafConfigs: Map<String, EvaluatorLeafConfig>
)
