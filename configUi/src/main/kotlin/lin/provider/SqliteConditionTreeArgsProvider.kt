package lin.provider

import lin.repository.condition_tree.ConditionTreeArgsRepository
import lin.serviceLoader.provider.ConditionTreeArgsProvider

class SqliteConditionTreeArgsProvider(
    private val repository: ConditionTreeArgsRepository
) : ConditionTreeArgsProvider {
    override fun findById(treeId: String): Map<String, Any>? = repository.findById(treeId)
}
