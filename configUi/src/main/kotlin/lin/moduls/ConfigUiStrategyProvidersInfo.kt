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

    // 共享：一次加载所有启用的绑定，三个 Provider 各自 derive 视图
    single { get<CardGroupService>().loadAll(onlyEnabled = true).flatMap { it.bindings } }

    single<CardGroupIndexProvider> {
        val bindings: List<CardGroupBinding> = get()
        object : CardGroupIndexProvider {
            override fun provide(): Map<String, Set<String>> = buildCardGroupIndex(bindings)
        }
    }

    single<BindingCardIdProvider> {
        val bindings: List<CardGroupBinding> = get()
        object : BindingCardIdProvider {
            override fun provide(): Map<String, List<String>> = buildBindingCardIds(bindings)
        }
    }

    single<GroupBehaviorProvider> {
        val bindings: List<CardGroupBinding> = get()
        object : GroupBehaviorProvider {
            override fun provide(): List<CardGroupBinding> = bindings
        }
    }
}

class ConfigUiStrategyProvidersInfo : ModulesInfo {
    override fun loadModules(): Module = strategyProviderModule
}

private fun buildCardGroupIndex(bindings: List<CardGroupBinding>): Map<String, Set<String>> {
    val index = linkedMapOf<String, MutableSet<String>>()
    bindings.forEach { binding ->
        binding.cardIds.forEach { cardId ->
            index.getOrPut(cardId) { linkedSetOf() }.add(binding.id)
        }
    }
    return index.mapValues { (_, groupIds) -> groupIds.toSet() }
}

private fun buildBindingCardIds(bindings: List<CardGroupBinding>): Map<String, List<String>> {
    val index = linkedMapOf<String, MutableList<String>>()
    bindings.forEach { binding ->
        index[binding.id] = binding.cardIds.toMutableList()
    }
    return index
}
