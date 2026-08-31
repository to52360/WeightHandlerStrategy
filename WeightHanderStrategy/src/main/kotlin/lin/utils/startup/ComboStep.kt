package lin.utils.startup

import lin.bean.usePlan.ComboPlanDefinition
import lin.domain.use.plan.ComboRuntime
import lin.serviceLoader.provider.ComboPlanDefinitionProvider
import lin.utils.runCatchingLog
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * 加载 combo 编排定义（Koin: ComboPlanDefinitionProvider），失败则降级为空定义。
 *
 * **T-012**：同一份定义在本次 contribute 里同时装配到 [ComboRuntime]（运行时索引，
 * 供谓词组命中的卡补全 combo 条目）。两者**同源且原子**——运行时对完整组集合重算的
 * 结果必然是静态预算的超集，不会丢静态部分（详见 ComboRuntime 类注释）。
 */
class ComboStep : ConfigBindingStep, KoinComponent {
    override fun contribute(builder: CardCombinedConfigBuilder) {
        val definitions = loadComboDefinitions()
        builder.comboDefinitions = definitions
        ComboRuntime.configure(definitions)
    }

    private fun loadComboDefinitions(): List<ComboPlanDefinition> {
        return runCatchingLog("加载 ComboPlanDefinitionProvider 失败，使用空 combo 编排定义") {
            get<ComboPlanDefinitionProvider>()
        }.getOrNull()?.findAll() ?: emptyList()
    }
}
