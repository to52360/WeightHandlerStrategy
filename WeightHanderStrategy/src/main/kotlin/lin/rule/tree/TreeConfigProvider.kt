package lin.rule.tree

interface TreeConfigProvider {
    fun findById(id: String): EvaluatorTreeConfig?
    fun findAll(): List<EvaluatorTreeConfig>
}
