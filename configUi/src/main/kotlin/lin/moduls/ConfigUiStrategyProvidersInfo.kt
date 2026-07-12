package lin.moduls

import lin.provider.SqliteCardPurposeProvider
import lin.provider.SqliteComboPlanDefinitionProvider
import lin.provider.SqliteConditionTreeConfigProvider
import lin.provider.SqliteTreeConfigProvider
import lin.serviceLoader.module.ModulesInfo
import lin.serviceLoader.provider.*
import lin.ui.card_group.db.CardGroupBehaviorRepository
import lin.ui.card_group.db.CardGroupRepository
import lin.ui.card_group.db.CardGroupService
import lin.ui.card_purpose.DefaultPurposeTagProvider
import lin.ui.card_purpose.PurposeTagProvider
import lin.ui.card_purpose.PurposeTagTreeBindingPolicy
import lin.ui.card_purpose.db.CardPurposeRepository
import lin.ui.combo_plan.db.ComboPlanDefinitionRepository
import lin.ui.condition_tree.db.ConditionTreeConfigRepository
import lin.ui.condition_tree.db.ConditionTreeConfigService
import lin.ui.condition_tree.db.createConditionTreeConfigMapper
import lin.ui.service.createTreeConfigMapper
import lin.ui.tree_config.db.TreeConfigRepository
import lin.rule.tree.CardGroupBinding
import org.koin.core.module.Module
import org.koin.dsl.module

val strategyProviderModule = module {
    single { CardGroupBehaviorRepository(get()) }
    single { CardGroupRepository(get(), get()) }
    single { CardGroupService(get()) }

    single {
        ComboPlanDefinitionRepository(get())
    }

    single {
        CardPurposeRepository(get())
    }

    single<ComboPlanDefinitionProvider> {
        SqliteComboPlanDefinitionProvider(get(), get())
    }

    single<CardPurposeProvider> {
        SqliteCardPurposeProvider(get())
    }

    single<PurposeTagProvider> { DefaultPurposeTagProvider() }
    single { PurposeTagTreeBindingPolicy(get()) }

        single<TreeConfigProvider> {
        SqliteTreeConfigProvider(
            repository = TreeConfigRepository(get()),
            mapper = createTreeConfigMapper(),
            groupRepository = CardGroupRepository(get(), get()),
            tagPolicy = get()
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
        val svc = get<CardGroupService>()
        object : CardGroupIndexProvider {
            override fun provide(): Map<String, Set<String>> = svc.loadCardGroupIndex()
        }
    }

    single<BindingCardIdProvider> {
        object : BindingCardIdProvider {
            override fun provide(): Map<String, List<String>> =
                get<CardGroupService>().loadBindingCardIds()
        }
    }

    single<GroupBehaviorProvider> {
        object : GroupBehaviorProvider {
            override fun provide(): List<CardGroupBinding> =
                get<CardGroupService>().loadAll(onlyEnabled = true).flatMap { it.bindings }
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
