package lin.utils.startup

import lin.bean.CardCombinedConfig
import lin.bean.usePlan.DefaultPurposeTagIntentRuleProvider
import lin.bean.usePlan.PurposeTagStore
import lin.serviceLoader.provider.PurposeTagIntentRuleProvider
import lin.serviceLoader.provider.StartupTask
import lin.utils.runCatchingLog
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.context.loadKoinModules
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * 启动期卡牌配置绑定任务：仅负责编排各 [ConfigBindingStep] 并注册最终产物到 Koin。
 * 具体数据源加载与组装逻辑分散在各自的 Step 中，本类不感知任何数据细节。
 */
class CardConfigBindingTask(
    private val steps: List<ConfigBindingStep>
) : StartupTask, KoinComponent {

    override fun execute() {
        // T-TG-006：用途意图规则走 SPI（configUi 提供落库实现）；无 configUi（纯引擎运行）
        // 或加载失败时回落内置硬编码 —— 禁止静默退化为「全无规则」
        // （那会让所有标签同时失去 stage / N / replan 默认值，且表面无异常）。
        val intentRuleProvider = runCatchingLog("加载 PurposeTagIntentRuleProvider 失败，回落内置硬编码规则") {
            get<PurposeTagIntentRuleProvider>()
        }.getOrNull() ?: DefaultPurposeTagIntentRuleProvider()

        val builder = CardCombinedConfigBuilder(intentRuleProvider)
        steps.forEach { it.contribute(builder) }
        val finalMap = builder.build()

        loadKoinModules(module {
            single<Map<String, CardCombinedConfig>>(named("weightInfo")) { finalMap }
            single { PurposeTagStore(tags = builder.cardPurposes.mapValues { it.value.purposeTags }) }
        })
    }
}
