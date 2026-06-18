package lin.provider

import lin.bean.usePlan.ComboPlanDefinition
import lin.card_group.db.CardGroupRepository
import lin.combo_plan.db.ComboPlanDefinitionRepository
import lin.serviceLoader.provider.ComboPlanDefinitionProvider

class SqliteComboPlanDefinitionProvider(
    private val repository: ComboPlanDefinitionRepository,
    private val cardGroupRepository: CardGroupRepository
) : ComboPlanDefinitionProvider {
    override fun findAll(): List<ComboPlanDefinition> {
        val enabledIds = cardGroupRepository.findManagers(onlyEnabled = true).map { it.id }.toSet()
        if (enabledIds.isEmpty()) return emptyList()
        return repository.findByManagerIds(enabledIds).map { it.toDomain() }
    }
}
