package lin.rule.context

import lin.bean.ComboCard
import lin.domain.MatchState
import lin.domain.MyWarManage
import lin.domain.PipelineCache
import lin.domain.WarInfo

/**
 * 规则执行上下文
 * 封装了出牌的基本信息，并为未来的"决策推演层（Inference Engine）"预留入口
 */
data class RuleContext(
    val callCard: ComboCard,
    val warInfo: WarInfo)

/**
 * RuleEnv 的默认实现
 * 直接从 MyWarManage 计算 WarView 快照 + 透传 MatchState + PipelineCache
 */
class WarInfoEnv(private val warManage: MyWarManage) : RuleEnv {
    override fun warView(): WarView = warManage.toWarView()
    override fun matchState(): MatchState = warManage.matchState
    override fun pipelineCache(): PipelineCache = warManage.pipelineCache
}
