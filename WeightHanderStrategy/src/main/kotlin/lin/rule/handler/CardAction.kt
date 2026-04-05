package lin.rule.handler

import club.xiaojiawei.hsscriptcardsdk.bean.Card
import lin.domain.use.UseAfterStrategy
import lin.domain.use.UseBeforeStrategy

/**
 * 暂时通用一个,后续太复杂采取config里面的模式
 */
data class ComboCardAction(
    // 使用 Int 和 Double 的可空版本来表示“可选更新”
    // 如果为 null，则不更新对应字段
    val useGroupId: Int? = null,
    val useGroupOrder: Double? = null,

    // 使用 List 而不是 MutableList 来保证 Intent 的不可变性，这是良好的实践
    val useAfterStrategies: List<UseAfterStrategy>? = null,
    val useBeforeStrategies: List<UseBeforeStrategy>? = null,

    val pointCard: Card? = null
)