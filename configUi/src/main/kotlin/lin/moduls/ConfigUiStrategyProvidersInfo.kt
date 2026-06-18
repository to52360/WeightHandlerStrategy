package lin.moduls

import lin.bean.usePlan.GroupUseOverride
import lin.card_group.db.CardGroupRepository
import lin.card_group.db.CardGroupService
import lin.card_purpose.DefaultPurposeTagProvider
import lin.card_purpose.PurposeTagProvider
import lin.card_purpose.PurposeTagTreeBindingPolicy
import lin.card_purpose.db.CardPurposeRepository
import lin.combo_plan.db.ComboPlanDefinitionRepository
import lin.condition_tree.db.ConditionTreeConfigRepository
import lin.condition_tree.db.ConditionTreeConfigService
import lin.condition_tree.db.createConditionTreeConfigMapper
import lin.provider.SqliteCardPurposeProvider
import lin.provider.SqliteComboPlanDefinitionProvider
import lin.provider.SqliteConditionTreeConfigProvider
import lin.provider.SqliteTreeConfigProvider
import lin.serviceLoader.module.ModulesInfo
import lin.serviceLoader.provider.*
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
            groupRepository = CardGroupRepository(get()),
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
            override fun provideBindingOverrides(): Map<String, GroupUseOverride> = svc.loadBindingOverrides()
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

private fun CardGroupService.loadBindingOverrides(): Map<String, GroupUseOverride> {
    return loadAll(onlyEnabled = true)
        .asSequence()
        .flatMap { it.bindings.asSequence() }
        .mapNotNull { binding ->
            val override = binding.overrides ?: return@mapNotNull null
            if (override.isDefault()) null else binding.id to override
        }
        .toMap()
}
