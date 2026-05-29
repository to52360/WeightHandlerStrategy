package lin.provider

import lin.bean.usePlan.ComboPlanDefinition
import lin.bean.usePlan.ComboPlanDefinitionProvider
import lin.combo_plan.db.ComboPlanDefinitionRepository

class SqliteComboPlanDefinitionProvider(
    private val repository: ComboPlanDefinitionRepository
) : ComboPlanDefinitionProvider {
    override fun findAll(): List<ComboPlanDefinition> {
        return repository.findAll().map { it.toDomain() }
    }
}
