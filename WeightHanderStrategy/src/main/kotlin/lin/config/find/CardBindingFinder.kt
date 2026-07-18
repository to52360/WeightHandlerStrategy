package lin.config.find

import lin.bean.CardWeightInfo
import lin.config.find.def.WeightInfoFinder
import lin.rule.tree.CardBindingId
import org.koin.core.component.KoinComponent

/**
 * 通过 CardBindingId (直接绑定卡牌) 查找 CardWeightInfo。
 * 包装为 CardBindingId 后直接利用底层 String finder (按 cardId 查找) 返回配置。
 */
class CardBindingFinder(
    private val cardIdFinder: WeightInfoFinder<String>
) : WeightInfoFinder<CardBindingId>, KoinComponent {

    override val targetType = CardBindingId::class

    override fun process(key: CardBindingId): List<CardWeightInfo> {
        return cardIdFinder.process(key.value)
    }
}
