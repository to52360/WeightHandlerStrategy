package lin.moduls

import lin.bean.usePlan.ComboPlanDefinitionProvider
import lin.card_group.db.CardGroupRepository
import lin.card_group.db.CardGroupService
import lin.card_purpose.db.CardPurposeRepository
import lin.combo_plan.db.ComboPlanDefinitionRepository
import lin.condition_tree.db.ConditionTreeConfigRepository
import lin.condition_tree.db.ConditionTreeConfigService
import lin.condition_tree.db.createConditionTreeConfigMapper
import lin.domain.use.plan.CardPurposeProvider
import lin.domain.use.plan.GroupUseOverrideProvider
import lin.group_use_override.db.GroupUseOverrideRepository
import lin.provider.*
import lin.rule.condition.ConditionTreeConfigProvider
import lin.rule.tree.TreeConfigProvider
import lin.serviceLoader.module.ModulesInfo
import lin.serviceLoader.provider.BindingCardIdProvider
import lin.serviceLoader.provider.CardGroupIndexProvider
import lin.tree_config.db.TreeConfigRepository
import lin.ui.service.createTreeConfigMapper
import org.koin.core.module.Module
import org.koin.dsl.module

val strategyProviderModule = module {
    single {
        CardGroupService(CardGroupRepository(get()))
    }

    single {
        ComboPlanDefinitionRepository(get())
    }

    single {
        CardPurposeRepository(get())
    }

    single {
        GroupUseOverrideRepository(get())
    }

    single<ComboPlanDefinitionProvider> {
        SqliteComboPlanDefinitionProvider(get())
    }

    single<CardPurposeProvider> {
        SqliteCardPurposeProvider(get())
    }

    single<GroupUseOverrideProvider> {
        SqliteGroupUseOverrideProvider(get())
    }

    single<TreeConfigProvider> {
        SqliteTreeConfigProvider(
            repository = TreeConfigRepository(get()),
            mapper = createTreeConfigMapper()
        )
    }

    single<ConditionTreeConfigProvider> {
        SqliteConditionTreeConfigProvider(
            service = ConditionTreeConfigService(
                repository = ConditionTreeConfigRepository(get()),
                mapper = createConditionTreeConfigMapper()
            )
        )
    }

    single<CardGroupIndexProvider> {
        object : CardGroupIndexProvider {
            override fun provide(): Map<String, Set<String>> = get<CardGroupService>().loadCardGroupIndex()
        }
    }

    single<BindingCardIdProvider> {
        object : BindingCardIdProvider {
            override fun provide(): Map<String, List<String>> = get<CardGroupService>().loadBindingCardIds()
        }
    }
}

class ConfigUiStrategyProvidersInfo : ModulesInfo {
    override fun loadModules(): Module = strategyProviderModule
}

private fun CardGroupService.loadCardGroupIndex(): Map<String, Set<String>> {
    val index = linkedMapOf<String, MutableSet<String>>()
    loadAll(onlyEnabled = true)
        .asSequence()
        .flatMap { it.bindings.asSequence() }
        .forEach { binding ->
            binding.cardIds.forEach { cardId ->
                index.getOrPut(cardId) { linkedSetOf() }.add(binding.id)
            }
        }
    return index.mapValues { (_, groupIds) -> groupIds.toSet() }
}

private fun CardGroupService.loadBindingCardIds(): Map<String, List<String>> {
    val index = linkedMapOf<String, MutableList<String>>()
    loadAll(onlyEnabled = true)
        .asSequence()
        .flatMap { it.bindings.asSequence() }
        .forEach { binding ->
            index[binding.id] = binding.cardIds.toMutableList()
        }
    return index
}
