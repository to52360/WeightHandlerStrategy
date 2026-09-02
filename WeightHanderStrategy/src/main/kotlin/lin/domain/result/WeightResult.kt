package lin.domain.result

import lin.bean.ComboCard
import lin.bean.passesFirstRoundCandidate
import lin.bean.passesSurplusCandidate
import lin.domain.context.NotWeight
import lin.domain.context.comboPenalty
import lin.domain.context.remainingCostPenalty
import lin.myLog

sealed class CmdPlanner
object ContinuePlanner : CmdPlanner()
class ResultPlanner(val weightResult: WeightResult) : CmdPlanner()

sealed class WeightResult {
    open fun weightSum(): Double {
        return NotWeight
    }

    open fun log() {
        myLog.info { "Empty" }
    }
    open fun toPlanner(): ResultPlanner {
        return ResultPlanner(this)
    }
}

object EmptyWeightResult : WeightResult() {
    val emptyWeightResult = ResultPlanner(this)
    override fun toPlanner(): ResultPlanner {
        return emptyWeightResult
    }

}

class EndWeightResult(
    val canUseCards: List<ComboCard>,
    val cost: Int,
    val findStrategy: FindBestCombination = DefaultFindBestCombination
) : WeightResult() {
    //todo-future 存在直接操作权重,导致查找不到元素 想改成ArrayList,太复杂了,后面再说
    private val _canUseCardsByHandler = mutableListOf<ComboCard>()

    //todo 不使用是不是断开,要核实一下
    val unUseCards: List<ComboCard>
        get() = _unUseCards
    private val _unUseCards: MutableList<ComboCard> by lazy {
        mutableListOf()
    }


    /** 主组合：第一轮（快路全收 / backtrack 搜索）选出 */
    private var mainCombination: List<ComboCard> = emptyList()

    /** 余费填充组合：fillSurplusCost 选出（T-021b 后技能入池同通道竞争，晚到候选重试机制已退役） */
    private var fillCombination: List<ComboCard> = emptyList()

    /** 最终组合 = 主组合 + 填充组合（既有消费方统一视角，T-020 拆分内部存储不动外部契约） */
    val bestCombination: List<ComboCard>
        get() = mainCombination + fillCombination

    var extWeight = 0.0

    /**
     * 处理权重之后的挫折
     */
    fun processWeightAfter(comboCard: ComboCard) {
        if (comboCard.isUnUse()) {
            _unUseCards.add(comboCard)

        } else _canUseCardsByHandler.add(comboCard)
    }
    fun addAll(comboCards: List<ComboCard>) {
        _canUseCardsByHandler.addAll(comboCards)
    }

    /**
     * 判断是否可以直接使用
     */
    fun isLessCost(): Boolean {
        val result = _canUseCardsByHandler.size == 1 || _canUseCardsByHandler.sumOf { it.cost() } < cost
        return result
    }

    override fun weightSum() = bestCombination.sumOf { it.powerWeight } + extWeight
    fun costSum() = bestCombination.sumOf { it.cost() }
    fun notAbleUseCards(): Boolean = _canUseCardsByHandler.isEmpty()

    override fun log() {
        myLog.info { "costSum: ${costSum()},weightSum: ${weightSum()},成员:${bestCombination}" }
    }

    fun lessAbleUseCards(): List<ComboCard> {
        // T-011/Q-009：不再因 isLessCost() 截断。落选卡 = 可用卡 - 已选组合，
        // 无论快路与否都是余费候选源（T-026 后被第一轮候选过滤挡掉的惜售牌等）。
        return _canUseCardsByHandler - bestCombination.toSet()
    }

    fun findBestCombination(isFull: Boolean = false, nDelta: Int = 0) {
        // T-008 + T-026：第一轮候选过滤——战术兑现（ts>0）或未配余费门槛（N==0）才进主组合；N>0 且战术未命中惜售。
        // 与 EvalOutcome.Banned 硬禁区分：只进不出候选，不改 unUse，卡保持可用。
        val firstRoundCandidates = _canUseCardsByHandler.filter { it.passesFirstRoundCandidate() }
        val fastPath = isLessCost()
        if (fastPath) {// 预评估快路：无替代组合，直接全收
            this.mainCombination = firstRoundCandidates
        } else {
            this.mainCombination = findStrategy.findBestCombination(firstRoundCandidates, cost)
        }

        // T-011/Q-009：同轮余费统筹填充——主牌选完后，用剩余费用填充余费牌并合并进同一组合，
        // 交由 UsePlanOrderer 全局按 UseStage 排序后一次性打出（否决两轮物理断层出牌）。
        fillSurplusCost(isFull, nDelta)

        if (fastPath) {
            // 快路 penalty 需在余费填充后重算：余费填充减少了剩余费用，penalty 应基于填充后的组合。
            // S-0.2: 原 `extWeight -= lessCost * CostWeight` 在 lessCost 大时产生离谱负数并污染
            // extWeight 通道。改为惩罚上限钳制为不超过本组合自身权重和，避免负分失控；
            // 最终量纲/是否保留由 Q-3 模型决策定。
            val lessCost = (cost - costSum()).coerceAtLeast(0)
            if (bestCombination.isNotEmpty()) {
                val baseWeightSum = bestCombination.sumOf { it.powerWeight }
                val totalPenalty = remainingCostPenalty(lessCost, cost) + comboPenalty(bestCombination.size)
                val penalty = totalPenalty.coerceAtMost(baseWeightSum)
                extWeight -= penalty
            }
        }
    }

    /**
     * T-011/Q-009：余费统筹填充（选牌算法第二梯队，非执行阶段断层）。
     *
     * 主牌已选后，在剩余费用内填充余费候选（[passesSurplusCandidate] 含 D-007/D-012 余费门槛：空闲 ≥ 牌费+N 或战术命中；
     * D-011 nDelta 绝望门槛减量按血量阶梯降低 N，地板 1），目标函数 `max Σ surplusFillValue`
     * （D-007 费数机会成本，[SurplusFillCombination]），
     * 合并进 [bestCombination]。排序与出牌由 UsePlanOrderer / useCombo 统一处理。
     * 战场已满时不出随从（isFull && isMinion），与执行层 UseFunction 的硬拦截一致。
     */
    private fun fillSurplusCost(isFull: Boolean, nDelta: Int = 0) {
        val remainingCost = cost - costSum()
        if (remainingCost < 0) return // 理论不可达（主牌总费用 ≤ cost），防御
        val candidates = (_canUseCardsByHandler - bestCombination.toSet())
            .filter { it.passesSurplusCandidate(remainingCost, isFull, nDelta) }
        if (candidates.isEmpty()) return
        this.fillCombination = SurplusFillCombination.findBestCombination(candidates, remainingCost)
    }

    /**
     * ⚠️ T-041：当前**无调用方**（死代码）。
     *
     * 它是 [ComboCard.canUse] 的唯一生产消费方，因此「BaseWeight 归零会让低分牌跌破
     * `powerWeight >= NotWeight` 门槛」这一担忧在生产路径上不成立——本方法没人调用。
     * 保留仅为未来「中途插牌」场景；启用前需重新评估 canUse 的门槛语义。
     */
    fun addUseCard(comboCard: ComboCard) {
        //myLog.info { "中途添加卡牌,卡牌为:${comboCard}" }
        if (comboCard.canUse()) fillCombination += comboCard
        else _canUseCardsByHandler.add(comboCard)
    }

}

inline fun WeightResult.compareSumWeight(
    compareWeight: WeightResult,
    extWeight: Double,
    action: (WeightResult) -> Unit
): WeightResult {
    if (this.weightSum() + extWeight >= compareWeight.weightSum()) {
        action(this)
        return this
    } else {
        return compareWeight
    }

}
