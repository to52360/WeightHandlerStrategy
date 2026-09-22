package lin.moduls

import lin.provider.*
import lin.repository.aura_boost.AuraBoostConfigService
import lin.repository.aura_boost.AuraBoostRepository
import lin.repository.card_group.*
import lin.repository.card_purpose.CardPurposeRepository
import lin.repository.card_purpose.PurposeTagDefRepository
import lin.repository.card_purpose.PurposeTagRuleRepository
import lin.repository.combo_plan.ComboPlanDefinitionRepository
import lin.repository.condition_tree.ConditionTreeConfigRepository
import lin.repository.condition_tree.ConditionTreeConfigService
import lin.repository.condition_tree.createConditionTreeConfigMapper
import lin.repository.delete_snapshot.DeleteSnapshotRepository
import lin.repository.delete_snapshot.SnapshotStore
import lin.repository.tree_config.EvaluatorLeafConfigRepository
import lin.repository.tree_config.TreeConfigRepository
import lin.rule.tree.CardGroupBinding
import lin.serviceLoader.module.ModulesInfo
import lin.serviceLoader.provider.*
import lin.ui.card_purpose.PurposeTagProvider
import lin.ui.card_purpose.PurposeTagTreeBindingPolicy
import lin.ui.card_purpose.SqlitePurposeTagIntentRuleProvider
import lin.ui.card_purpose.SqlitePurposeTagProvider
import lin.ui.condition_tree.validation.ConditionTreeValidator
import lin.ui.service.TreeConfigService
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

    // 同 T-008 理由：下列两个 bean 在 configUi 侧只注册于 uiDBModule（UI 进程自己的 Koin），
    // 而本模块被引擎**单独**经 SPI 装载 ⇒ 引擎侧解析 TreeConfigService / ConditionTreeConfigService
    // 时会 NoDefinitionFoundException（评估树/条件树整体拿不到）。故在此自备，保持本模块自洽。
    single { TreeConfigRepository(get()) }
    single { ConditionTreeValidator(get()) }

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
    // T-TG-007：用途意图规则落库（第一层排序兜底）；引擎经 CardConfigBindingTask 从 Koin 解析
    single { PurposeTagRuleRepository(get()) }
    // T-TG-015：用途预设（锚 + 维度项单表）+ 卡组增量项，配置侧解析
    single { StrategyPresetRepository(get()) }
    single { StrategyPresetService(get(), get(), get()) }
    single { DimensionItemResolver() }
    single { CurrentDeckContext(get()) }
    single<PurposeTagIntentRuleProvider> {
        SqlitePurposeTagIntentRuleProvider(get(), get(), get(), get())
    }
    single { PurposeTagTreeBindingPolicy(get()) }

    // K-TG-007：评估树组装必须走 TreeConfigService（config_data 只存 root，叶子在 evaluator_leaf_config 表）
    single { EvaluatorLeafConfigRepository(get()) }
    // T-TG-021：快照域唯一入口（表存取 + 删除编排）
    single { DeleteSnapshotRepository(get()) }
    single { SnapshotStore(get(), get()) }
    single { TreeConfigService(get(), get(), createTreeConfigMapper(), get()) }
    single<TreeConfigProvider> {
        SqliteTreeConfigProvider(
            treeConfigService = get(),
            cardGroupService = get(),
            tagPolicy = get(),
            presetRepository = get(),
            currentDeck = get(),
            resolver = get()
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

    single { AuraBoostRepository(get()) }
    single {
        AuraBoostConfigService(
            repository = get(),
            conditionTreeService = get(),
            conditionTreeMapper = createConditionTreeConfigMapper(),
            tx = get()
        )
    }
    single<AuraBoostConfigProvider> {
        // D-DP-001：全局行走「预设白名单 ∪ 卡组增量 − exclude」闸门 ⇒ 追加维度项仓储 / 当前卡组 / 合并纯函数
        SqliteAuraBoostConfigProvider(
            service = get(),
            cardGroupService = get(),
            presetRepository = get(),
            currentDeck = get(),
            resolver = get()
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
