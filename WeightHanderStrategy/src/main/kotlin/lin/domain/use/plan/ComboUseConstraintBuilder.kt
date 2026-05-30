package lin.domain.use.plan

import lin.bean.ComboCard
import lin.bean.groupIds
import lin.bean.usePlan.MustUseGroupBefore
import lin.bean.usePlan.UseConstraint


data class ComboUseConstraints(
    val useConstraints: List<UseConstraint>
)

object ComboUseConstraintBuilder {
    /**
     * 根据已选中的牌和启动期预解析的编排绑定，生成使用顺序约束。
     *
     * 这里不计算分数，也不决定“该不该选这些牌”：
     * - 选牌和 combo 加权继续留在 FindBestCombination / FindComboStrategy。
     * - 本对象只消费 CardComboUseBinding，不读取评分用的 CardComboBinding。
     */
    fun build(cards: List<ComboCard>): ComboUseConstraints {
        val useConstraints = cards
            .flatMap { it.comboUseBindings() }
            .distinctBy { "${it.comboId}:${it.beforeGroupIds}:${it.afterGroupIds}" }
            .filter { cards.anyCardInGroups(it.beforeGroupIds) && cards.anyCardInGroups(it.afterGroupIds) }
            .map {
                MustUseGroupBefore(
                    beforeGroupIds = it.beforeGroupIds,
                    afterGroupIds = it.afterGroupIds,
                    reason = "combo:${it.comboId} before"
                )
            }

        return ComboUseConstraints(useConstraints)
    }

    /**
     * 判断卡牌分组是否命中定义里的任意分组。
     */
    private fun List<ComboCard>.anyCardInGroups(groupIds: Set<String>): Boolean =
        any { it.groupIds().intersects(groupIds) }

    private fun Set<String>.intersects(other: Set<String>): Boolean = any { it in other }
}
