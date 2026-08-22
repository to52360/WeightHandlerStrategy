package lin.serviceLoader.weightRule.onWar.rival

import lin.bean.AllCleanCard
import lin.bean.CleanWarId
import lin.bean.ComboCard
import lin.config.CardConfig
import lin.config.UseConfig
import lin.domain.context.UnUseWeight
import lin.myLog
import lin.serviceLoader.weightRule.utils.war.*


/**
 * 用于方便单卡权重实现
 */
abstract class ReleaseWar(var warCardGap: Int) : CleanWar(CleanWarId) {

    override fun name(): String {
        return "解场用的之aoe"
    }
    override fun cardConfigs(): List<CardConfig> {
        return listOf(UseConfig(useGroupId, useStrategyList = listOf(this)), AllCleanCard)
    }
    override fun calWeight(callCard: ComboCard): Double {
        val damage = cache.getDamageById(callCard)
        return if (damage == ALL_CLEAN) {
            processAll()
        } else {
            processHasDamage(damage)
        }
    }

    /**
     * 全清理和有限清理
     * 暂时分为两个方法,后续有大改动再分为两个对象
     */
    private fun processAll(): Double {
        val warStatus = cleanWarUtils.warStatus
        //溢出伤害太严重
        var warCardGap = this.warCardGap
        // 虽然修改低攻清场问题,但是遇到buff类就有问题了
        if (!warStatus.isAdv()) { //没优势缩减数量要求
            //todo-future 这里减2,当血量太少会疯狂解场,可接受场攻受血量影响
            warCardGap += if (warStatus.excessDamageFactor() >= 2 * ONE_FACTOR) -1
            //高费有价值才值得清理
            else if (cleanWarUtils.worthTarget.any { it.cost > 2 }) -1 else 0
        }
        if (cleanWarUtils.rivalNumLessGap(warCardGap)) {
            return UnUseWeight
        }
        // 实验性,有优势不清理
        if (!warStatus.isAdv())
            cleanWar(ALL_CLEAN)
        if (cleanWarUtils.lessGap(warCardGap, ALL_CLEAN)) {
            return UnUseWeight
        }

        //避免没优势时候没清场
        cleanWar(ALL_CLEAN)
        //伤害达不到标准就降低
        return if (warStatus.isAdvByMeAtc()) groupWeight + unConditionWeight else groupWeight
    }

    /**
     * 有限伤害清理
     */
    private fun processHasDamage(damage: Int): Double {
        //这里能可以把血量压下去也可以考虑,或者有价值目标清理成残血也可以考虑
        val warCardGap = cleanWarUtils.hasWorthReduceNum(this.warCardGap)
        if (cleanWarUtils.rivalNumLessGap(warCardGap)) {
            return UnUseWeight
        }
        if (cleanWarUtils.meAtcOverRivalTaunt())//再多条件换设计模式
            cleanWar(damage)

        if (cleanWarUtils.lessGap(warCardGap, damage)) {
            val warStatus = cleanWarUtils.warStatus
            if (warStatus.isAdvByMeAtc())
                return UnUseWeight
        }

        //todo-future 配置字段不够,暂时使用代码定义配置
        var cutWeight = unConditionWeight * cleanWarUtils.unPassRate(damage)
        //能接受的剩余血量
        val ableLessBlood = 3
        //存在值得清理
        var hasNotWorth = true


        //要不要没达到场攻就降权重,能清理有价值减少降低的权重,但是也要降低权重
        if (cleanWarUtils.hasWorthTarget() && cleanWarUtils.worthTarget.any { it.blood() < damage + ableLessBlood }) {
            myLog.info { "能降低关键目标减少降低的权重,降低之前:${cutWeight}(会除以2)" }
            cutWeight /= 2
            hasNotWorth = false
        }
        cutWeight += cleanWarUtils.getExcessDamageFixWeight(damage)

        val weight = groupWeight + cutWeight

        return if (hasNotWorth && !cleanWarUtils.isHasAvg()) {
            //这样下回合有buff就麻烦了,虽然避免小场面清场
            myLog.info { "没有有价值目标且达不到指定平均攻击力,将降低权重,默认为2" }
            weight / 2
        } else weight


    }

    open fun cleanWar(damage: Int) {
        cleanWarUtils.cleanOnce(damage)
    }


}

/**
 *
 */
class DepNumRelWarByAll : ReleaseWar(0) {

    override fun setNum(num: Int) {
        this.warCardGap = num

    }
}



