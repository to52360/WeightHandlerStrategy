package lin.provider

import lin.bean.usePlan.ComboPlanDefinition
import lin.repository.card_group.CardGroupRepository
import lin.repository.combo_plan.ComboPlanDefinitionRepository
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
