package lin.serviceLoader.parse

import lin.config.Rules
import lin.config.find.def.BindInfo
import lin.myLog
import lin.serviceLoader.provider.BindInfoProvider
import lin.serviceLoader.weightRule.CardRule
import lin.utils.serviceLoader.ServiceLoaderUtils


/**
 * 单卡条件绑定并绑定
 */

class ParseCardRule : BindInfoProvider {
    override fun provide(): List<BindInfo> {
        val bindInfos = mutableListOf<BindInfo>()
        ServiceLoaderUtils.loadServices(CardRule::class.java).forEach { cardRule ->
            myLog.info { "单卡规则加载到:${cardRule.cardId()}" }
            bindInfos.add(BindInfo(cardRule.cardId(), Rules(cardRule)))
        }
        return bindInfos
    }
}