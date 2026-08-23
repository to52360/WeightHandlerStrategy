package lin.rule.tree

data class EvaluatorTreeConfig(
    val bindingType: EvaluatorTreeBindingType,
    val bindingIds: List<String>,
    val root: EvaluatorNode,
    val leafConfigs: Map<String, EvaluatorLeafConfig>
)
