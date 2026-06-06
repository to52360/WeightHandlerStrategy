package lin.config.find

import lin.bean.CardWeightInfo
import lin.bean.usePlan.PurposeTagId
import lin.bean.usePlan.PurposeTagStore
import lin.config.find.def.WeightInfoFinder
import lin.rule.tree.PurposeTagBindingId
import org.koin.core.component.KoinComponent

/**
 * 通过用途标签 [PurposeTagBindingId] 查找 CardWeightInfo。
 *
 * 从 [PurposeTagStore] 反向索引找到持有该标签的所有卡牌 id，
 * 再委托 cardIdFinder 查找 CardWeightInfo。
 * 作为 ConfigDispatcher 按 PURPOSE_TAG 绑定的路由目标。
 */
class PurposeTagFinder(
    private val cardIdFinder: WeightInfoFinder<String>
) : WeightInfoFinder<PurposeTagBindingId>, KoinComponent {

    override val targetType = PurposeTagBindingId::class

    /** 用途标签 → cardId 列表的惰性反向索引 */
    private val tagIndex: Map<PurposeTagId, Set<String>> by lazy {
        val store = getKoin().get<PurposeTagStore>()
        val merged = linkedMapOf<PurposeTagId, MutableSet<String>>()
        store.tags.forEach { (cardId, tags) ->
            tags.forEach { tag ->
                merged.getOrPut(tag) { linkedSetOf() }.add(cardId)
            }
        }
        merged
    }

    override fun process(key: PurposeTagBindingId): List<CardWeightInfo> {
        val tagId = PurposeTagId(key.value)
        val cardIds = tagIndex[tagId] ?: return emptyList()
        return cardIds.flatMap { cardIdFinder.process(it) }
    }
}
