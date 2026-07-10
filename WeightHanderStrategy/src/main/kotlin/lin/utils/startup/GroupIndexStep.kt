package lin.utils.startup

import lin.serviceLoader.provider.CardGroupIndexProvider
import lin.utils.serviceLoader.ServiceLoaderUtils

/**
 * 加载卡牌分组索引 + 分组级行为覆盖（SPI: CardGroupIndexProvider）。
 */
class GroupIndexStep : ConfigBindingStep {
    override fun contribute(builder: CardCombinedConfigBuilder) {
        ServiceLoaderUtils.loadServices(CardGroupIndexProvider::class.java).forEach { provider ->
            provider.provide().forEach { (cardId, groupIds) ->
                builder.groupMap.getOrPut(cardId) { linkedSetOf() }.addAll(groupIds)
            }
            builder.groupOverrides.putAll(provider.provideBindingOverrides())
        }
    }
}
