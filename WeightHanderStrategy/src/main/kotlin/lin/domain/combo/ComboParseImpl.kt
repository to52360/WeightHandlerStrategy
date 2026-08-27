package lin.domain.combo

import lin.bean.*


/**
 * T-002：旧「最后使用」排序通道弃用——写入 useGroupId/useGroupOrder 已无排序消费方（排序全切 UseStage/UsePlanOrderer）。
 * 保留注册仅因 comboType=last 的旧 DB 数据仍走此解析链；新「先打/后打」约束语义走 ComboPlanDefinition.relation → MustUseGroupBefore。
 */
class LastUseCombo : ValidBindComboParse {

    override fun processBindCombo(
        cardWeightInfos: List<CardWeightInfo>, comboInfo: ComboInfo
    ) {
        cardWeightInfos.forEach {
            it.useGroupId = LastUseGroupId
            it.useGroupOrder = comboInfo.comboWeight
        }
    }

}

/**
 * T-002：旧「最先使用」排序通道弃用，理由同上 [LastUseCombo]。
 */
class FirstUseCombo : ValidBindComboParse {

    override fun processBindCombo(
        cardWeightInfos: List<CardWeightInfo>, comboInfo: ComboInfo
    ) {
        cardWeightInfos.forEach {
            it.useGroupId = FirstUseGroupId
            it.useGroupOrder = comboInfo.comboWeight
        }
    }

}

/**
 *同组加权
 */
open class ComboImpl : ValidDepComboParse, ComboPredicateByGroup {
    override fun processBindCombo(
        cardWeightInfos: List<CardWeightInfo>,
        comboInfo: ComboInfo
    ) {
        val comboRule: ComboRule = predicateByGroup(comboInfo)
        val combo = Combo(comboInfo.infoId, comboRule, comboInfo.comboType)
        //赋值
        cardWeightInfos.forEach {
            it.addCombo(combo)
        }
    }

}

