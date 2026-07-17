package lin.utils.startup

import lin.bean.ConfigSlice
import lin.bean.ConfigSliceScope
import lin.bean.SliceEntry
import lin.domain.use.UseActionRegistry
import lin.rule.tree.CardGroupBehavior
import lin.serviceLoader.provider.GroupBehaviorProvider
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * 加载配置侧「分组行为」并存入 builder。
 * - OVERRIDE → builder.groupBehaviors（build 时 expandGroupBehaviors 展开到 groupOverrides）
 * - USE_ACTION → builder.slices（Scope.Group，build 时 expandSlices 翻译到 cardId）
 *
 * 新增来源：加对应 Step 往 builder.slices 加 SliceEntry，builder 零改动。
 */
class GroupBehaviorStep : ConfigBindingStep, KoinComponent {
    override fun contribute(builder: CardCombinedConfigBuilder) {
        val bindings = get<GroupBehaviorProvider>().provide()
        builder.groupBehaviors = bindings
        for (binding in bindings) {
            for (behavior in binding.behaviors) {
                if (behavior is CardGroupBehavior.UseActionBehavior && behavior.useActions.isNotEmpty()) {
                    val strategies = behavior.useActions.map { UseActionRegistry.resolve(it, behavior.extraConfig) }
                    builder.slices += SliceEntry(
                        scope = ConfigSliceScope.Group(binding.id),
                        slice = ConfigSlice(useStrategies = strategies)
                    )
                }
            }
        }
    }
}
