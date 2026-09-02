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
 *
 * **生命周期 = 一次决策批次**：实例由 [MyWarManage.ruleEnv] 持有并复用，任何改变战场/手牌的动作
 * （[lin.domain.use.tryUseCard] / [MyWarManage.reLoad]）都会使其失效重建。
 * 快照语义（[warView] 只算一次、[cache] 跨卡共享）**依赖该失效契约**成立——
 * 切勿在本类之外长期持有实例，否则会读到过期战场。
 */
class WarInfoEnv(private val warManage: MyWarManage) : RuleEnv {
    private val evalCache = mutableMapOf<String, Any?>()

    override fun warInfo(): WarInfo = warManage

    // 真·快照：原先每次调用都 toWarView()，条件树里多处读战场会重复计算整份 WarView。
    // 惰性到首次访问，一次批次内只算一次（批次内战场不变，见类 KDoc 失效契约）。
    private val warViewSnapshot by lazy(LazyThreadSafetyMode.NONE) { warManage.toWarView() }

    override fun warView(): WarView = warViewSnapshot

    override fun matchState(): MatchState = warManage.matchState

    @Suppress("UNCHECKED_CAST")
    override fun <T> cache(key: String, compute: () -> T): T =
        evalCache.getOrPut(key) { compute() } as T
}
