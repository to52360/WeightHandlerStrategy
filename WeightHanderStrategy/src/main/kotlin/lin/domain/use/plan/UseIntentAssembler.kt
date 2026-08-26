package lin.domain.use.plan

import lin.bean.usePlan.CardPurpose
import lin.bean.usePlan.CardUseConfig
import lin.bean.usePlan.GroupUseOverride
import lin.bean.usePlan.UseIntent

/**
 * 使用意图组装器：持有启动期原始配置，自解析 cardId → [UseIntent]。
 *
 * 作为栈上实例创建，Task 结束后随帧回收。
 * 优先级：GroupUseOverride > CardPurpose > 默认值。
 */
class UseIntentAssembler(
    private val cardPurposes: Map<String, CardPurpose>,
    private val groupMap: Map<String, Set<String>>,
    private val groupOverrides: Map<String, GroupUseOverride>,
    private val deriver: UseIntentDeriver = UseIntentDeriver()
) {

    fun assemble(cardId: String): UseIntent {
        val cardPurpose = cardPurposes[cardId] ?: CardPurpose()
        val groupsSet = groupMap[cardId] ?: emptySet()
        val groupOverride = groupsSet.firstNotNullOfOrNull { groupOverrides[it] }

        val config = CardUseConfig(
            purposeTags = cardPurpose.purposeTags,
            stageOverride = groupOverride?.stageOverride,
            replanAfterUse = groupOverride?.replanAfterUse
                ?: cardPurpose.replanAfterUse,
            orderWeight = groupOverride?.orderWeight ?: 0.0
        )
        return deriver.derive(config)
    }
}
