package lin.utils.startup

import lin.bean.CardCombinedConfig
import lin.bean.usePlan.PurposeTagStore
import lin.serviceLoader.provider.StartupTask
import org.koin.core.context.loadKoinModules
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * 启动期卡牌配置绑定任务：仅负责编排各 [ConfigBindingStep] 并注册最终产物到 Koin。
 * 具体数据源加载与组装逻辑分散在各自的 Step 中，本类不感知任何数据细节。
 */
class CardConfigBindingTask(
    private val steps: List<ConfigBindingStep>
) : StartupTask {

    override fun execute() {
        val builder = CardCombinedConfigBuilder()
        steps.forEach { it.contribute(builder) }
        val finalMap = builder.build()

        loadKoinModules(module {
            single<Map<String, CardCombinedConfig>>(named("weightInfo")) { finalMap }
            single { PurposeTagStore(tags = builder.cardPurposes.mapValues { it.value.purposeTags }) }
        })
    }
}
