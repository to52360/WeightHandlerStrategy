package lin.moduls

import lin.card_group.repository.CardGroupRepository
import lin.card_group.service.CardGroupService
import lin.provider.SqliteTreeConfigProvider
import lin.rule.tree.TreeConfigProvider
import lin.serviceLoader.module.ModulesInfo
import lin.serviceLoader.provider.BindingCardIdProvider
import lin.serviceLoader.provider.CardGroupIndexProvider
import lin.tree_config.repository.TreeConfigRepository
import lin.tree_config.service.createTreeConfigMapper
import org.koin.core.module.Module
import org.koin.dsl.module

val strategyProviderModule = module {
    single {
        CardGroupService(CardGroupRepository(get()))
    }

    single<TreeConfigProvider> {
        SqliteTreeConfigProvider(
            repository = TreeConfigRepository(get()),
            mapper = createTreeConfigMapper()
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
