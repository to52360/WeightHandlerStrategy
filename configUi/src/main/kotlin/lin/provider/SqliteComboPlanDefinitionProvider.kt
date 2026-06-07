package lin.provider

import lin.bean.usePlan.ComboPlanDefinition
import lin.combo_plan.db.ComboPlanDefinitionRepository
import lin.serviceLoader.provider.config.ComboPlanDefinitionProvider

class SqliteComboPlanDefinitionProvider(
    private val repository: ComboPlanDefinitionRepository
) : ComboPlanDefinitionProvider {
    override fun findAll(): List<ComboPlanDefinition> {
        return repository.findAll().map { it.toDomain() }
    }
}
