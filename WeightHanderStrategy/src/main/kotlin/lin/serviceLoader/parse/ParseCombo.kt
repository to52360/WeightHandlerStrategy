package lin.serviceLoader.parse

import lin.bean.CardWeightInfo
import lin.domain.combo.ComboParse
import lin.myLog
import lin.weightHandler.condition.config.ComboInfoDao
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.error.NoDefinitionFoundException
import org.koin.core.qualifier.named

/**
 * 解析combo  打出顺序的重构
 */
class ParseCombo : ParseCardWeightInfo, KoinComponent {
    override fun parse(infoMap: Map<String, CardWeightInfo>) {
        val cardGroupInfos = infoMap.values.groupBy { it.groupId }
        val comboInfoDao = get<ComboInfoDao>()
        val comboInfos = comboInfoDao.findAll()
        comboInfos.forEach { comboInfo ->
            try {
                val comboParse = get<ComboParse>(named(comboInfo.comboType))
                comboParse.parse(cardGroupInfos, comboInfo)
            } catch (e: NoDefinitionFoundException) {
                myLog.error(e) {
                    "id${comboInfo.infoId}:对应的类型不支持${comboInfo.comboType}"
                }
            }


        }
    }
}