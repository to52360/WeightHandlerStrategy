package lin.rule.handler

import club.xiaojiawei.hsscriptcardsdk.bean.Card
import lin.domain.use.UseAfterStrategy
import lin.domain.use.UseBeforeStrategy

/**
 * 暂时通用一个,后续太复杂采取config里面的模式
 */
data class ComboCardAction(
    // T-002：旧排序通道弃用（同 UseConfig.useGroupId），规则动作不再经其影响排序，仅保留写入以兼容旧数据。
    val useGroupId: Int? = null,
    val useGroupOrder: Double? = null,

    // 使用 List 而不是 MutableList 来保证 Intent 的不可变性，这是良好的实践
    val useAfterStrategies: List<UseAfterStrategy>? = null,
    val useBeforeStrategies: List<UseBeforeStrategy>? = null,

    val pointCard: Card? = null
)