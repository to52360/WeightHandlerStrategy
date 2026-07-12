package lin.utils.startup

import lin.domain.use.UseActionRegistry
import lin.domain.use.UseStrategy
import lin.rule.tree.findOverride
import lin.rule.tree.findUseActions
import lin.serviceLoader.provider.GroupBehaviorProvider
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * 加载配置侧「分组行为」并写入 builder（Koin: GroupBehaviorProvider）。
 *
 * 同一数据源单次遍历、就地消费，按行为类型分流到不同 builder 字段：
 * - OVERRIDE → builder.groupOverrides（最终进 CardCombinedConfig.useIntent）
 * - USE_ACTION → builder.useStrategiesByCardId（最终进 CardCombinedConfig.useStrategies，before/after 由消费端分流）
 *
 * 编码侧声明的 UseConfig.useStrategyList（如 ReleaseWar/CleanWar）仍走 ConfigDispatcher→UseConfigHandler
 * 写入 CardWeightInfo，不在此处理，避免重复。运行期注入的动作（SkillFindStrategy/RuleTreeBinding）
 * 同理留在 CardWeightInfo/ComboCard。
 */
class GroupBehaviorStep : ConfigBindingStep, KoinComponent {
    override fun contribute(builder: CardCombinedConfigBuilder) {
        get<GroupBehaviorProvider>().provide().forEach { binding ->
        //todo 这里要不要when
            binding.behaviors.findOverride()?.let { override ->
                builder.groupOverrides[binding.id] = override
            }
            val actions = binding.behaviors.findUseActions()
            if (actions.isNotEmpty()) {
                val strategies: List<UseStrategy> = actions.map { UseActionRegistry.resolve(it) }
                binding.cardIds.forEach { cardId ->
                    builder.useStrategiesByCardId.getOrPut(cardId) { ArrayList() }.addAll(strategies)
                }
            }
        }
    }
}
