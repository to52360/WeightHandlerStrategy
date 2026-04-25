package lin.serviceLoader.weightRule

import lin.bean.ComboCard
import lin.domain.WarInfo
import lin.rule.handler.RuleLevel
import lin.rule.handler.RuleResult

/**
 * 表达意图规则接口
 */
interface IntentRule : RuleId {
    val ruleLevel: RuleLevel
    fun intentCmd(callCard: ComboCard, warInfo: WarInfo): RuleResult
}

interface RuleId {
    fun id(): String {
        return this.javaClass.simpleName
    }
}

interface WeightRule : RuleId {
    /**
     * 根据战场
     * @param callCard 需要处理的的卡牌,todo-future 要不要去掉 这里传入是为了处理完权重信息一起处理combo组情景,
     * @param warInfo 战场信息
     */
    fun calculateWeight(callCard: ComboCard, warInfo: WarInfo): Double
}

interface RuleInfo : GroupWeight, RuleId {

    fun name(): String {
        return this.javaClass.simpleName
    }

    fun description() = name()
}


/**
 * 单卡权重规则
 *
 * 可组合使用接口
 * [lin.lifecycle.LifecycleRegister]
 */
interface CardRule : WeightRule {
    override fun id(): String = cardId()
    fun cardId(): String

}










