package lin.domain.context

import lin.config.EngineConfig
import kotlin.math.pow
import kotlin.math.sqrt

// 下面常量的值由 engine.properties 控制，EngineConfig 提供默认值。
// 运行时可通过系统属性或外部配置文件覆盖（见 engine.properties 注释）。

/** 一费权重值，打不满费用时仍然可打出 */
val CostWeight: Double get() = EngineConfig.costWeight

//todo 这里是不是要删了
val MaxCostWeight: Double get() = EngineConfig.maxCostWeight
val NotWeight: Double get() = EngineConfig.notWeight
val BaseWeight: Double get() = EngineConfig.baseWeight
val OrderWeight: Double get() = EngineConfig.orderWeight
val UnUseWeight: Double get() = EngineConfig.unUseWeight

val PenaltyRatioExponent: Double get() = EngineConfig.penaltyRatioExponent
val PenaltyWeight: Double get() = EngineConfig.penaltyWeight
val ComboCardWeight: Double get() = EngineConfig.comboCardWeight
val CostValueWeight: Double get() = EngineConfig.costValueWeight
val CostValueExponent: Double get() = EngineConfig.costValueExponent
val CostValueMaxCost: Double get() = EngineConfig.costValueMaxCost
val SpellCostValueWeight: Double get() = EngineConfig.spellCostValueWeight

// Q-024 量纲费化（T-PV-011）：评估树/combo/AuraBoost 直接配费值，tacticalScoreScale 已退役。
// 战术分不再有「分→费」换算层——双量纲（选牌层 1分=1费 / 填充层 1分=0.4费）消灭。

// 费用价值凹函数：costValue(cost, weight) = weight * min(cost, MaxCost)^CostValueExponent。
// 表达炉石「低费抢节奏溢价、高费卡手/怕解贬值」的非线性经济规律（指数 0.5 = √cost）。
// 三类输入：① 配置等效费用（powerWeight，weight=CostValueWeight）；② 随从等效费用 (atc+hp)/2（实时身材，weight=CostValueWeight）；
// ③ 法术初始费用（数据库，weight=SpellCostValueWeight，更保守）。
// 上限 MaxCost（默认 10）封顶「夸张身材」（如实时 buff 到 30/30）与异常费用，避免基础价值虚高。
fun costValue(cost: Double, weight: Double = CostValueWeight): Double =
    weight * cost.coerceAtMost(CostValueMaxCost).pow(CostValueExponent)

// 剩余法力惩罚原语：使用解耦后的 PenaltyWeight (使 PenaltyWeight < CostWeight，防止低费单卡负分)
// 【量纲：费派生标量】—— 自变量 remainingCost/totalCost 全为【费】，输出是「费」的非线性标量（√剩余费 × 占比^指数）。
// ⚠️ 该值被主搜索当作【分】从目标函数里扣（`− penalty`，与 baseValue 同轴扣减）⇒ **量纲不成立**；
//    系数 PenaltyWeight(0.35) 究竟在费轴还是分轴标定无从追溯。
//    **A-合流版（D-FO-005 / T-FO-014）未覆盖本点**——本任务只统一了 ts/aura/combo 三路来源，
//    惩罚项与机制牌换算率仍属遗留失配点（见 KNOWN-DEFECTS K-FO-003、K-FO-002）。
fun remainingCostPenalty(remainingCost: Int, totalCost: Int): Double {
    if (totalCost <= 0) return 0.0
    val ratio = remainingCost.toDouble() / totalCost.toDouble()
    return PenaltyWeight * sqrt(remainingCost.toDouble()) * ratio.pow(PenaltyRatioExponent)
}

// 组合非线性复杂性惩罚：(n-1)^1.5 * ComboCardWeight，防止多卡垃圾堆砌
// 【量纲：无量纲】—— 纯张数驱动（张数差 ^1.5 × 经验系数 0.1），不携带任何费用信息，
// 是「张数→扣分」的经验标量；因无量纲故并入任一轴都不产生量纲矛盾（可在分轴侧保留）。
fun comboPenalty(cardsCount: Int): Double {
    if (cardsCount <= 1) return 0.0
    return (cardsCount - 1).toDouble().pow(1.5) * ComboCardWeight
}

/**
 * D-FO-005 A-合流版：树分/光环分/组合分（**费**）折算成有效**分** = `costValue(E + ts) − costValue(E)`。
 *
 * 语义 =「条件命中 ⇒ 这张牌等效**超模 ts 费**」：E 是卡牌固有等效费（[lin.bean.equivalentCostValue]），
 * `E + ts` 是命中后的总等效费；两者都经同一条 `costValue` 凹函数折成分，差值即战术收益。
 * 由此**凹函数的边际递减同样作用于战术收益**——同一份 ts 对贵牌贡献小、对便宜牌贡献大
 * （实算：E=4.5 的 ts=3.2 → 1.96 分；E=9 的 ts=3.2 → 0.49 分，后者因 `E+ts` 超上限被封顶）。
 *
 * 消费侧一次换算后落存 [lin.bean.ComboCard.tacticalContribution] / `auraContribution`（方案 B 分量落存），
 * 故主搜索与日志拿到的是**分**，与 [lin.bean.ComboCard.baseValue]（分）同轴直加合法。
 *
 * ## 定义域保护（三条，均不可省）
 * 1. 哨兵直通：`ts == [UnUseWeight]`（−100）原样返回、不参与换算——它是「绝对禁出」哨兵而非费用值
 *    （Q-024：「禁出只走 BAN，禁止配 −100 表达禁出；哨兵语义不参与换算」）。若对它做换算，
 *    −100 会被压成 `−costValue(E)`（约 −9.5），[lin.domain.WeightHandlerDomain.processWeight] 的
 *    `calWeight == UnUseWeight ⇒ unUse()` 硬禁分支将**永不命中**，绝对禁出的牌会静默复活。
 * 2. NaN 防护：`ts` 非有限时返回 0.0。NaN 会经 `total` 污染 `powerWeight` ⇒ `canUse()` 恒 false，
 *    牌被静默判为不可用且不带 `unUse` 标记（无痕故障）。当前无构造 NaN 树分的路径，此为防御。
 * 3. 下界钳制：负 ts 可能使 `E + ts` 跌破 0 费，而 `costValue` 对负底数返回 NaN。故把自变量饱和到 0：
 *    `E + ts ≤ 0` ⇒ 贡献 = `−costValue(E)`（亏到 0 费地板为止，不再继续变负）。上界由 `costValue`
 *    自身的 `MaxCost` 封顶承担。
 */
fun tacticalContribution(equivalentCost: Double, ts: Double): Double {
    if (ts == 0.0) return 0.0
    if (ts == UnUseWeight) return ts
    if (!ts.isFinite() || !equivalentCost.isFinite()) return 0.0
    val base = equivalentCost.coerceAtLeast(0.0)
    val shifted = (equivalentCost + ts).coerceAtLeast(0.0)
    // @verify K-FO-004: 上界由 costValue 的 MaxCost 封顶承担，高费牌（E=9,ts=3.2）战术贡献被压到 0.4868（不封顶则 1.4785）
    return costValue(shifted) - costValue(base)
}




// 等待/动画时序 (millis)
val AwaitAnimationTime: Long get() = EngineConfig.awaitAnimationTime
val UseAnimationTime: Long get() = EngineConfig.useAnimationTime
val ChangeAnimationTime: Long get() = EngineConfig.changeAnimationTime
val FourAnimationTime: Long get() = EngineConfig.awaitAnimationTime * 4






