package lin.moduls

import lin.bean.CardCombinedConfig
import lin.config.ConfigDispatcher
import lin.di.CONFIG_BINDING_STEPS
import lin.di.configModule
import lin.di.infraModule
import lin.di.ruleModule
import lin.serviceLoader.provider.*
import lin.utils.startup.ConfigBindingStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.nio.file.Files

/**
 * 引擎启动链的**装配护栏**（T-FO-006）：把「引擎 modules + [strategyProviderModule]」装进一个 bare Koin 容器，
 * 逐个解析引擎启动真正会用到的东西——缺任何一个定义都会抛 `NoDefinitionFoundException`。
 *
 * ## 为什么测试在 configUi 而不是引擎
 *
 * [strategyProviderModule] 属于 configUi（经 `ModulesInfo` SPI 提供给引擎），引擎**零反向依赖** configUi，
 * 故引擎侧测试无法引用它 —— 这正是它长期零覆盖的原因。反过来 configUi 依赖引擎，测试放这里才装得起来。
 *
 * ## 为什么必须有本测试
 *
 * 该模块**只在真实引擎进程里被装载**：MCP/UI 两个入口（`loadMcpModules` / `loadUiModules`）都走
 * `uiDBModule`、**不含它**。于是它的自洽性零覆盖 —— 2026-09-22 就因此连撞两次：
 * `TreeConfigRepository` / `ConditionTreeValidator` 只在 `uiDBModule` 注册，导致引擎解析
 * `TreeConfigService` / `ConditionTreeConfigService` 时抛 `NoDefinitionFoundException`
 * （**评估树 + 条件树整体拿不到**），而当时全量单测是绿的。
 *
 * ## 同时守住的第二件事
 *
 * Koin 把定义按 **(primaryType, qualifier)** 归档 —— 同类型 + 无条件符的多次 `single<>` 会互相覆盖。
 * 故本测试断言 `getAll<ConfigBindingStep>()` 拿到**全部 7 个步骤**（而非最后一个）。
 */
class StrategyProviderModuleWiringTest {

    private fun engineWiringKoin() = koinApplication {
        val dbPath = Files.createTempFile("engine-wiring-", ".db").toAbsolutePath().toString()
        modules(
            module {
                // 真实引擎里由 SqliteJdbcProvider 提供；此处用临时库替代（仓库构造时会建表，故需真实连接）
                single<JdbcTemplate> { JdbcTemplate(DriverManagerDataSource("jdbc:sqlite:$dbPath")) }
                // 真实引擎里由 CardConfigBindingTask.execute() 动态注册；finder 构造时需要它
                single(named("weightInfo")) { emptyMap<String, CardCombinedConfig>() }
            },
            infraModule,
            configModule,
            ruleModule,
            strategyProviderModule,
        )
    }

    @Test
    fun `引擎侧容器能解析全部启动依赖与配置 Provider`() {
        val koin = engineWiringKoin().koin
        val resolved = mutableListOf<String>()

        // 与各 ConfigBindingStep / StartupTask 的取用一一对应（缺一个即抛）
        koin.get<PurposeTagIntentRuleProvider>().also { resolved += "PurposeTagIntentRuleProvider" }
        koin.get<ComboPlanDefinitionProvider>().also { resolved += "ComboPlanDefinitionProvider" }
        koin.get<CardPurposeProvider>().also { resolved += "CardPurposeProvider" }
        koin.get<CardGroupIndexProvider>().also { resolved += "CardGroupIndexProvider" }
        koin.get<GroupBehaviorProvider>().also { resolved += "GroupBehaviorProvider" }
        koin.get<BindingCardIdProvider>().also { resolved += "BindingCardIdProvider" }
        koin.get<TreeConfigProvider>().also { resolved += "TreeConfigProvider" }
        koin.get<ConditionTreeConfigProvider>().also { resolved += "ConditionTreeConfigProvider" }
        koin.get<AuraBoostConfigProvider>().also { resolved += "AuraBoostConfigProvider" }
        // 树绑定链（2026-09-22 finder 覆盖 bug 的现场）
        koin.get<ConfigDispatcher>().also { resolved += "ConfigDispatcher" }
        // 启动任务（CardConfigBindingTask 构造时即解析全部步骤）
        val startupTasks = koin.getAll<StartupTask>().also { resolved += "StartupTask×${it.size}" }
        println(">>> resolved=$resolved")

        assertTrue("启动任务不应为空", startupTasks.isNotEmpty())
    }

    @Test
    fun `七个配置绑定步骤一个都不能少`() {
        val steps = engineWiringKoin().koin.get<List<ConfigBindingStep>>(named(CONFIG_BINDING_STEPS))
        val names = steps.map { it::class.simpleName }
        println(">>> steps=${names.size} $names")

        assertEquals(
            "步骤数量不对 ⇒ 某个配置维度不会参与装配。实际: $names",
            7,
            steps.size,
        )
    }
}
