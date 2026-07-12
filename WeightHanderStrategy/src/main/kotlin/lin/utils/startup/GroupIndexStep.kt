package lin.utils.startup

import lin.serviceLoader.provider.CardGroupIndexProvider
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * 加载卡牌分组索引（Koin: CardGroupIndexProvider）。
 * 分组行为（OVERRIDE / USE_ACTION）由 [GroupBehaviorStep] 统一消费。
 */
class GroupIndexStep : ConfigBindingStep, KoinComponent {
    override fun contribute(builder: CardCombinedConfigBuilder) {
        get<CardGroupIndexProvider>().provide().forEach { (cardId, groupIds) ->
            builder.groupMap.getOrPut(cardId) { linkedSetOf() }.addAll(groupIds)
        }
    }
}
