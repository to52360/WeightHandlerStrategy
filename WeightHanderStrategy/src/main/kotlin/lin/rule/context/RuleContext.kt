package lin.rule.context

import lin.bean.ComboCard
import lin.domain.MatchState
import lin.domain.MyWarManage
import lin.domain.WarInfo

/**
 * 规则执行上下文：仅封装当前决策目标（正在评估的卡牌）。
 * 战场状态归 [RuleEnv.warInfo]，不在 Context 中持有。
 * 为未来的"决策推演层（Inference Engine）"预留入口。
 */
data class RuleContext(
    val callCard: ComboCard
)

/**
 * RuleEnv 的默认实现
 * 直接从 MyWarManage 透传 WarInfo + 计算 WarView 快照 + 透传 MatchState
 */
class WarInfoEnv(private val warManage: MyWarManage) : RuleEnv {
    private val evalCache = mutableMapOf<String, Any?>()

    override fun warInfo(): WarInfo = warManage
    override fun warView(): WarView = warManage.toWarView()
    override fun matchState(): MatchState = warManage.matchState

    @Suppress("UNCHECKED_CAST")
    override fun <T> cache(key: String, compute: () -> T): T =
        evalCache.getOrPut(key) { compute() } as T
}
