package lin.config.find

import lin.bean.CardWeightInfo
import lin.config.find.def.WeightInfoFinder
import lin.rule.tree.BindingGroupId
import lin.serviceLoader.provider.BindingCardIdProvider
import org.koin.core.component.KoinComponent

/**
 * 通过 BindingGroupId 查找 CardWeightInfo。
 * 先将 bindingId 解析为 cardId 列表，再复用 String finder 查找。
 */
class BindingGroupFinder(
    private val cardIdFinder: WeightInfoFinder<String>
) : WeightInfoFinder<BindingGroupId>, KoinComponent {

    override val targetType = BindingGroupId::class

    private val bindingIndex: Map<String, List<String>> by lazy {
        val merged = linkedMapOf<String, MutableList<String>>()
        getKoin().getAll<BindingCardIdProvider>().forEach { provider ->
            provider.provide().forEach { (bindingId, cardIds) ->
                merged.getOrPut(bindingId) { mutableListOf() }.addAll(cardIds)
            }
        }
        merged
    }

    override fun process(key: BindingGroupId): List<CardWeightInfo> {
        val cardIds = bindingIndex[key.value] ?: return emptyList()
        return cardIds.flatMap { cardIdFinder.process(it) }
    }
}
