package lin.serviceLoader.weightRule.onWar.rival

import lin.bean.CleanCard
import lin.bean.ComboCard
import lin.bean.DefUseGroupId
import lin.config.CardConfig
import lin.config.UseConfig
import lin.domain.WarInfo
import lin.domain.context.ChangeAnimationTime
import lin.domain.context.NotWeight
import lin.domain.context.UnUseWeight
import lin.domain.use.UseAfterStrategy
import lin.domain.use.UseContext
import lin.domain.use.UseDomain
import lin.serviceLoader.weightRule.ExtConfig
import lin.serviceLoader.weightRule.onWar.rival.utils.DamageCache
import lin.serviceLoader.weightRule.utils.abs.AbsWeightCondition
import lin.serviceLoader.weightRule.utils.war.*
import lin.warExt.my.base.resource
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

abstract class CleanWar(
    // T-002：旧排序通道弃用——该 useGroupId 仅经 UseConfig 写入 CardWeightInfo.useGroupId，已无排序消费方，
    // 保留不改以适配旧配置；新解场/释放排序语义走 UseStage（DEFEND/CLEAR）与 stageOverride。
    val useGroupId: Int
) : AbsWeightCondition(), KoinComponent, UseAfterStrategy, ExtConfig {
    companion object {
        //无伤害视为全部清理
        const val ALL_CLEAN: Int = 0
    }

    protected val cleanWarUtils: CleanWarUtils = get()
    protected val cache = DamageCache()
    override fun calculateWeight(callCard: ComboCard, warInfo: WarInfo): Double {
        var weight = calWeight(callCard)
        if (weight > NotWeight) {
            //溢出伤害太高加权重
            val warStatus = warInfo.warStatus
            val ableAtc = acceptableRivalAttack(warInfo.resource())
            val excessDamageFactor = warStatus.excessDamage / ableAtc
            val maxFactor = 2
            if (excessDamageFactor >= maxFactor) weight *= 2

        }
        return weight
    }


    override fun afterExtAction(context: UseContext, useDomain: UseDomain) {
        context.extraAwaitMillis = ChangeAnimationTime
        context.replanRequested = true
    }

    abstract fun calWeight(callCard: ComboCard): Double
}

class DepNumRelWar : CleanWar(DefUseGroupId) {
    override fun calWeight(callCard: ComboCard): Double {
        if (cleanWarUtils.compareRivalNum(cleanWarUtils.hasWorthReduceNum(number))) return UnUseWeight
        val damage = cache.getDamageById(callCard)
        if (damage == ALL_CLEAN) return groupWeight
        val cutWeight =
            unConditionWeight * cleanWarUtils.unPassRate(damage) + cleanWarUtils.getExcessDamageFixWeight(damage)
        return groupWeight + cutWeight
    }
    override fun cardConfigs(): List<CardConfig> {
        return listOf(UseConfig(useGroupId, useStrategyList = listOf(this)), CleanCard)
    }

    override fun description(): String {
        return "单向解场"
    }
}
