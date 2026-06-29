package lin.provider

import lin.bean.usePlan.ComboPlanDefinition
import lin.serviceLoader.provider.ComboPlanDefinitionProvider
import lin.ui.card_group.db.CardGroupRepository
import lin.ui.combo_plan.db.ComboPlanDefinitionRepository

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
