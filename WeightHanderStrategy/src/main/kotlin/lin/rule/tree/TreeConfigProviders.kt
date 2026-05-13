package lin.rule.tree

import lin.utils.serviceLoader.ServiceLoaderUtils

object TreeConfigProviders {
    private val providers: List<TreeConfigProvider> by lazy {
        ServiceLoaderUtils.loadServices(TreeConfigProvider::class.java)
    }

    fun findById(id: String): EvaluatorTreeConfig? {
        return providers.firstNotNullOfOrNull { it.findById(id) }
    }

    fun findAll(): List<EvaluatorTreeConfig> {
        return providers.flatMap { it.findAll() }
    }
}
