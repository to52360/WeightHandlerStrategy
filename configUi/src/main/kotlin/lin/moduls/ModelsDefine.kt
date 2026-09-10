package lin.moduls

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import lin.bean.usePlan.DefaultPurposeTagIntentRuleProvider
import lin.bean.usePlan.PurposeTagIntentRuleProvider
import lin.config.PathConfig
import lin.di.infraModule
import lin.repository.HsCardRepository
import lin.repository.OrthogonalTemplateRepository
import lin.repository.TemplateGroupRepository
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
import lin.repository.delete_snapshot.DeleteSnapshotRepository
import lin.repository.delete_snapshot.DeleteSnapshotService
import lin.repository.tree_config.EvaluatorLeafConfigRepository
import lin.repository.tree_config.EvaluatorLeafSourceCatalog
import lin.repository.tree_config.EvaluatorTreeTemplateRepository
import lin.repository.tree_config.TreeConfigRepository
import lin.ui.SelectOptionRegistry
import lin.ui.UiExtension
import lin.ui.aura_boost.AuraBoostExtension
import lin.ui.card_group.ActiveManagerHolder
import lin.ui.card_group.CardGroupExtension
import lin.ui.card_purpose.CardPurposeExtension
import lin.ui.card_purpose.PurposeTagProvider
import lin.ui.card_purpose.PurposeTagTreeBindingPolicy
import lin.ui.card_purpose.SqlitePurposeTagProvider
import lin.ui.combo_plan.ComboPlanExtension
import lin.ui.condition_tree.ConditionTreeExtension
import lin.ui.condition_tree.action.ConditionTreeWorkbenchAction
import lin.ui.condition_tree.action.CreateConditionTreeAction
import lin.ui.condition_tree.action.DeleteConditionTreeAction
import lin.ui.condition_tree.action.SaveConditionTreeAction
import lin.ui.condition_tree.validation.ConditionTreeValidator
import lin.ui.service.*
import lin.ui.tree_config.EvaluatorTreeExtension
import lin.ui.tree_config.action.*
import org.koin.core.context.GlobalContext.startKoin
import org.koin.dsl.bind
import org.koin.dsl.module
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Files
import javax.sql.DataSource


/**
 * 业务服务模块——不依赖 JavaFX，MCP server 也需要加载。
 * SPI 基础设施（RuleRegistry/PipelineAssembler/ConditionRegistry/ScoreOperatorRegistry）
 * 已提取至 WeightHandlerStrategy 的 [infraModule]，避免与 [ruleModule] 重复。
 */
val serviceModule = module {

    single { SelectOptionRegistry() }

    // 标记定义与显示（T-TG-001：定义落库，白名单随之可增长）
    single { PurposeTagDefRepository(get()) }
    single<PurposeTagProvider> { SqlitePurposeTagProvider(get()) }
    single<PurposeTagIntentRuleProvider> { DefaultPurposeTagIntentRuleProvider() }
    single { PurposeTagTreeBindingPolicy(get()) }

    // 全局卡组选择状态
    single { ActiveManagerHolder() }

}

val uiModule = module {

    // UI 扩展注册
    single { CardGroupExtension() } bind UiExtension::class
    single { EvaluatorTreeExtension() } bind UiExtension::class
    single { ConditionTreeExtension() } bind UiExtension::class
    single { CardPurposeExtension() } bind UiExtension::class
    single { ComboPlanExtension() } bind UiExtension::class
    single { AuraBoostExtension() } bind UiExtension::class

    // 评估树工作台动作注册
    single { CreateNewTreeAction() } bind TreeWorkbenchAction::class
    single { CreateFromTemplateAction() } bind TreeWorkbenchAction::class
    single { EditTreePropertiesAction() } bind TreeWorkbenchAction::class
    single { SaveTreeAction() } bind TreeWorkbenchAction::class
    single { SaveAsTemplateAction() } bind TreeWorkbenchAction::class
    single { DeleteTreeAction() } bind TreeWorkbenchAction::class

    // 条件树工作台动作注册
    single { CreateConditionTreeAction() } bind ConditionTreeWorkbenchAction::class
    single { SaveConditionTreeAction() } bind ConditionTreeWorkbenchAction::class
    single { DeleteConditionTreeAction() } bind ConditionTreeWorkbenchAction::class

}

/**
 * 数据库基础模块：创建 SQLite 数据源、[JdbcTemplate] 与事务模板 [TransactionTemplate]。
 *
 * T-008（2026-09-05）：SQLite 是 **单写者 + 文件级锁**，池化多连接反增 `SQLITE_BUSY` 竞争，
 * 故固定 `maximumPoolSize=1` + `minimumIdle=1`（常驻）——等价于 **单物理连接**：
 * hs 库 ATTACH 只执行一次，也不会像旧配置（`minIdle=0` + `idleTimeout=30s`）那样空闲即销毁、
 * 重连时重复 ATTACH。
 * 保留 Hikari 而非换 `SingleConnectionDataSource` 的关键理由：连接池提供 **借用互斥**——
 * 同一时刻只有一个线程持有连接，事务的 BEGIN/COMMIT 不会被其他线程的交错调用破坏
 * （SQLite serialized 模式只保护单个 API 调用，**不保护事务边界**）。
 */
val dbModule = module {
    single<DataSource> {
        val dbPath = PathConfig.databasePath
        if (!Files.exists(dbPath)) {
            Files.createFile(dbPath)
        }
        val config = HikariConfig().apply {
            driverClassName = "org.sqlite.JDBC"
            jdbcUrl = "jdbc:sqlite:${dbPath.toAbsolutePath()}"
            maximumPoolSize = 1
            minimumIdle = 1
            idleTimeout = 0 // 常驻：禁用空闲回收，避免重连时重复 ATTACH
            connectionTimeout = 10_000
            connectionTestQuery = "SELECT 1"
            connectionInitSql = "ATTACH DATABASE '${PathConfig.hsCardsDbPath.toAbsolutePath()}' AS hs"
            poolName = "ConfigUiPool"
        }
        HikariDataSource(config)
    }

    single<JdbcTemplate> { JdbcTemplate(get<DataSource>()) }

    // T-008：多表 / 级联多步写的事务保证。项目用 Koin（非 Spring 容器），
    // `@Transactional` 注解不生效（无 Spring AOP 代理），故统一用 TransactionTemplate 手动包裹。
    single<TransactionTemplate> { TransactionTemplate(DataSourceTransactionManager(get<DataSource>())) }
}
val uiDBModule = module {
    single { TreeConfigRepository(get()) }
    single { EvaluatorTreeTemplateRepository(get()) }
    single { EvaluatorLeafConfigRepository(get()) }
    single { TreeConfigService(get(), get(), createTreeConfigMapper(), get()) }
    single { EvaluatorTreeTemplateService(get(), get(), createTreeConfigMapper()) }
    single { EvaluatorTreeResolver(get(), get()) }
    single { ConditionTreeConfigRepository(get()) }
    single {
        ConditionTreeConfigService(
            get(),
            createConditionTreeConfigMapper(),
            get()
        )
    }
    single { AuraBoostRepository(get()) }
    single { ConditionTreeValidator(get()) }
    // T-011：saveWithInlineTrees 组合编排注入条件树服务 / mapper / 事务模板
    single { AuraBoostConfigService(get(), get(), createConditionTreeConfigMapper(), get()) }
    single { EvaluatorLeafSourceCatalog(get(), get(), get()) }
    single { CardGroupBehaviorRepository(get()) }
    single { CardGroupRepository(get(), get()) }
    single { CardGroupService(get(), get()) }
    // T-011：delete(card_group) 级联删除事务下沉（Provider 层不再持 tx）
    single { CardGroupCascadeDeleteService(get(), get(), get()) }
    single { CardPurposeRepository(get()) }
    single { HsCardRepository(get()) }

    single { ComboPlanDefinitionRepository(get()) }
    single { TemplateGroupRepository(get()) }
    single { OrthogonalTemplateRepository(get()) }
    // T-010：delete 快照落 delete_snapshot 表 + restore_snapshot 一键恢复
    single { DeleteSnapshotRepository(get()) }
    single {
        DeleteSnapshotService(
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get()
        )
    }
}


/** 加载完整模块（含 JavaFX UI 扩展，不含 MCP） */
fun loadUiModules() {
    startKoin {
        modules(infraModule, serviceModule, dbModule, uiDBModule, uiModule)
    }
}

    

