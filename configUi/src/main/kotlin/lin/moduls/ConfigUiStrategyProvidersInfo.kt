package lin.moduls

import lin.card_group.repository.CardGroupRepository
import lin.card_group.service.CardGroupService
import lin.card_group.service.SpiBindingCardIdProvider
import lin.provider.SpiCardGroupIndexProvider
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
    single<TreeConfigProvider> {
        SqliteTreeConfigProvider(
            repository = TreeConfigRepository(get()),
            mapper = createTreeConfigMapper()
        )
    }

    single<CardGroupIndexProvider> {
        SpiCardGroupIndexProvider(
            service = CardGroupService(CardGroupRepository(get()))
        )
    }

    single<BindingCardIdProvider> {
        SpiBindingCardIdProvider(
            service = CardGroupService(CardGroupRepository(get()))
        )
    }
}

class ConfigUiStrategyProvidersInfo : ModulesInfo {
    override fun loadModules(): Module = strategyProviderModule
}
