package lin.moduls

import lin.provider.*
import lin.repository.aura_boost.AuraBoostConfigService
import lin.repository.aura_boost.AuraBoostRepository
import lin.repository.card_group.CardGroupBehaviorRepository
import lin.repository.card_group.CardGroupRepository
import lin.repository.card_group.CardGroupService
import lin.repository.card_purpose.CardPurposeRepository
import lin.repository.card_purpose.PurposeTagDefRepository
import lin.repository.combo_plan.ComboPlanDefinitionRepository
import lin.repository.condition_tree.ConditionTreeConfigRepository
import lin.repository.condition_tree.ConditionTreeConfigService
import lin.repository.condition_tree.createConditionTreeConfigMapper
import lin.repository.tree_config.TreeConfigRepository
import lin.rule.tree.CardGroupBinding
import lin.serviceLoader.module.ModulesInfo
import lin.serviceLoader.provider.*
import lin.ui.card_purpose.PurposeTagProvider
import lin.ui.card_purpose.PurposeTagTreeBindingPolicy
import lin.ui.card_purpose.SqlitePurposeTagProvider
import lin.ui.service.createTreeConfigMapper
import org.koin.core.module.Module
import org.koin.dsl.module
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate

val strategyProviderModule = module {
    single { CardGroupBehaviorRepository(get()) }
    single { CardGroupRepository(get(), get()) }
    // T-008：本模块由引擎主程序经 SPI 装载（**不含 configUi 的 dbModule**，那里的 TransactionTemplate 解析不到），
    // 故自备事务模板——从 JdbcTemplate 反查 DataSource（引擎侧 SqliteJdbcProvider 构造 JdbcTemplate 时必带 DataSource）。
    single<TransactionTemplate> {
        val dataSource = requireNotNull(get<JdbcTemplate>().dataSource) { "引擎侧 JdbcTemplate 未绑定 DataSource" }
        TransactionTemplate(DataSourceTransactionManager(dataSource))
    }
    single { CardGroupService(get(), get()) }

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

    // T-TG-001：标记定义落库（本模块由引擎经 SPI 装载，JdbcTemplate 来自引擎侧）
    single { PurposeTagDefRepository(get()) }
    single<PurposeTagProvider> { SqlitePurposeTagProvider(get()) }
    single { PurposeTagTreeBindingPolicy(get()) }

        single<TreeConfigProvider> {
        SqliteTreeConfigProvider(
            repository = TreeConfigRepository(get()),
            mapper = createTreeConfigMapper(),
            groupRepository = CardGroupRepository(get(), get()),
            tagPolicy = get()
        )
    }

    // T-011：条件树配置服务提为单例（ConditionTreeConfigProvider 与 AuraBoostConfigService 共用）
    single {
        ConditionTreeConfigService(
            repository = ConditionTreeConfigRepository(get()),
            mapper = createConditionTreeConfigMapper(),
            conditionTreeValidator = get()
        )
    }
    single<ConditionTreeConfigProvider> {
        SqliteConditionTreeConfigProvider(service = get())
    }

    single<AuraBoostConfigProvider> {
        SqliteAuraBoostConfigProvider(
            service = AuraBoostConfigService(
                repository = AuraBoostRepository(get()),
                conditionTreeService = get(),
                conditionTreeMapper = createConditionTreeConfigMapper(),
                tx = get()
            ),
            cardGroupService = get()
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
