package lin.domain.combo

import lin.bean.CardWeightInfo
import lin.bean.ComboInfo
import lin.bean.ComboRule

/**
 * 发现策略配置信息,基于combo体系从而快速实现,存在耦合问题
 */
class ChangeComboParse : ValidDepComboParse, ComboPredicateByGroup {
    override fun processBindCombo(
        cardWeightInfos: List<CardWeightInfo>,
        comboInfo: ComboInfo
    ) {
        val comboRule: ComboRule = predicateByGroup(comboInfo)
        cardWeightInfos.forEach { it.addChangeComboRule(comboRule) }
    }
}

