package lin.moduls

import lin.bean.usePlan.GroupUseOverride
import lin.provider.SqliteCardPurposeProvider
import lin.provider.SqliteComboPlanDefinitionProvider
import lin.provider.SqliteConditionTreeConfigProvider
import lin.provider.SqliteTreeConfigProvider
import lin.serviceLoader.module.ModulesInfo
import lin.serviceLoader.provider.*
import lin.ui.service.createTreeConfigMapper
import lin.ui.tree_config.db.TreeConfigRepository
import org.koin.core.module.Module
import org.koin.dsl.module

val strategyProviderModule = module {
    single {
        _root_ide_package_.lin.ui.card_group.db.CardGroupService(
            _root_ide_package_.lin.ui.card_group.db.CardGroupRepository(
                get()
            )
        )
    }

    single {
        _root_ide_package_.lin.ui.combo_plan.db.ComboPlanDefinitionRepository(get())
    }

    single {
        _root_ide_package_.lin.ui.card_purpose.db.CardPurposeRepository(get())
    }

    single<ComboPlanDefinitionProvider> {
        SqliteComboPlanDefinitionProvider(get(), get())
    }

    single<CardPurposeProvider> {
        SqliteCardPurposeProvider(get())
    }

    single<lin.ui.card_purpose.PurposeTagProvider> { _root_ide_package_.lin.ui.card_purpose.DefaultPurposeTagProvider() }
    single { _root_ide_package_.lin.ui.card_purpose.PurposeTagTreeBindingPolicy(get()) }

    single<TreeConfigProvider> {
        SqliteTreeConfigProvider(
            repository = TreeConfigRepository(get()),
            mapper = createTreeConfigMapper(),
            groupRepository = _root_ide_package_.lin.ui.card_group.db.CardGroupRepository(get()),
            tagPolicy = get()
        )
    }

    single<ConditionTreeConfigProvider> {
        SqliteConditionTreeConfigProvider(
            service = _root_ide_package_.lin.ui.condition_tree.db.ConditionTreeConfigService(
                repository = _root_ide_package_.lin.ui.condition_tree.db.ConditionTreeConfigRepository(get()),
                mapper = _root_ide_package_.lin.ui.condition_tree.db.createConditionTreeConfigMapper()
            )
        )
    }

    single<CardGroupIndexProvider> {
        val svc = get<lin.ui.card_group.db.CardGroupService>()
        object : CardGroupIndexProvider {
            override fun provide(): Map<String, Set<String>> = svc.loadCardGroupIndex()
            override fun provideBindingOverrides(): Map<String, GroupUseOverride> = svc.loadBindingOverrides()
        }
    }

    single<BindingCardIdProvider> {
        object : BindingCardIdProvider {
            override fun provide(): Map<String, List<String>> =
                get<lin.ui.card_group.db.CardGroupService>().loadBindingCardIds()
        }
    }
}

class ConfigUiStrategyProvidersInfo : ModulesInfo {
    override fun loadModules(): Module = strategyProviderModule
}

private fun lin.ui.card_group.db.CardGroupService.loadCardGroupIndex(): Map<String, Set<String>> {
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

private fun lin.ui.card_group.db.CardGroupService.loadBindingCardIds(): Map<String, List<String>> {
    val index = linkedMapOf<String, MutableList<String>>()
    loadAll(onlyEnabled = true)
        .asSequence()
        .flatMap { it.bindings.asSequence() }
        .forEach { binding ->
            index[binding.id] = binding.cardIds.toMutableList()
        }
    return index
}

private fun lin.ui.card_group.db.CardGroupService.loadBindingOverrides(): Map<String, GroupUseOverride> {
    return loadAll(onlyEnabled = true)
        .asSequence()
        .flatMap { it.bindings.asSequence() }
        .mapNotNull { binding ->
            val override = binding.overrides ?: return@mapNotNull null
            if (override.isDefault()) null else binding.id to override
        }
        .toMap()
}
