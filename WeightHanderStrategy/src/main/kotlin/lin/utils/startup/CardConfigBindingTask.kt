package lin.utils.startup

import lin.bean.CardCombinedConfig
import lin.bean.CardWeightInfo
import lin.serviceLoader.cardInfoProvide.CardWeightInfoProvide
import lin.serviceLoader.provider.CardGroupIndexProvider
import lin.utils.serviceLoader.ServiceLoaderUtils
import org.koin.core.component.KoinComponent
import org.koin.core.context.loadKoinModules
import org.koin.core.qualifier.named
import org.koin.dsl.module

class CardConfigBindingTask : StartupTask, KoinComponent {

    override fun execute() {
        // 1. 统一提取：拉取基础权重 Map
        val baseInfos = HashMap<String, CardWeightInfo>()
        ServiceLoaderUtils.loadServices(CardWeightInfoProvide::class.java).forEach { provider ->
            baseInfos.putAll(provider.getInfos())
        }

        // 2. 统一提取：拉取卡牌分组 Map
        val groupMap = HashMap<String, MutableSet<String>>()
        ServiceLoaderUtils.loadServices(CardGroupIndexProvider::class.java).forEach { provider ->
            provider.provide().forEach { (cardId, groupIds) ->
                groupMap.getOrPut(cardId) { linkedSetOf() }.addAll(groupIds)
            }
        }

        // 3. 统一赋值组装只读 finalMap (扁平化，直接传入 groupIds)
        val finalMap: Map<String, CardCombinedConfig> = baseInfos.mapValues { (cardId, weightInfo) ->
            val groupsSet = groupMap[cardId] ?: emptySet()

            CardCombinedConfig(
                weightInfo = weightInfo,
                groupIds = groupsSet
            )
        }

        // 4. 🌟 动态向 Koin 注册不可变的完全体只读 Map！
        loadKoinModules(module {
            single<Map<String, CardCombinedConfig>>(named("weightInfo")) { finalMap }
        })
    }
}
