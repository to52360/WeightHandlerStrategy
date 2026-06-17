package lin.tree_config.ui.strategy

import lin.rule.build.DynamicFieldOption
import lin.serviceLoader.provider.SelectOptionProvider

class SideOptionProvider : SelectOptionProvider {
    override val dataSourceId: String = "side_types"

    override fun getOptions(): List<DynamicFieldOption> {
        return listOf(
            DynamicFieldOption(label = "我方", value = "ME"),
            DynamicFieldOption(label = "敌方", value = "RIVAL"),
            DynamicFieldOption(label = "双方", value = "BOTH")
        )
    }
}
