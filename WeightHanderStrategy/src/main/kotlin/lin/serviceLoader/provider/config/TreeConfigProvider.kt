package lin.serviceLoader.provider.config

import lin.rule.tree.EvaluatorTreeConfig

interface TreeConfigProvider {
    fun findById(id: String): EvaluatorTreeConfig?
    fun findAll(): List<EvaluatorTreeConfig>
}
