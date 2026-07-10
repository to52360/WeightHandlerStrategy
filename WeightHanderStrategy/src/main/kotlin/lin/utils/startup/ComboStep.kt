package lin.utils.startup

import lin.bean.usePlan.ComboPlanDefinition
import lin.serviceLoader.provider.ComboPlanDefinitionProvider
import lin.utils.runCatchingLog
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * 加载 combo 编排定义（Koin: ComboPlanDefinitionProvider），失败则降级为空定义。
 */
class ComboStep : ConfigBindingStep, KoinComponent {
    override fun contribute(builder: CardCombinedConfigBuilder) {
        builder.comboDefinitions = loadComboDefinitions()
    }

    private fun loadComboDefinitions(): List<ComboPlanDefinition> {
        return runCatchingLog("加载 ComboPlanDefinitionProvider 失败，使用空 combo 编排定义") {
            get<ComboPlanDefinitionProvider>()
        }.getOrNull()?.findAll() ?: emptyList()
    }
}
