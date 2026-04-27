package lin.rule.context

import lin.bean.ComboCard
import lin.domain.WarInfo

/**
 * 规则执行上下文
 * 封装了出牌的基本信息，并为未来的"决策推演层（Inference Engine）"预留入口
 */
data class RuleContext(
    val callCard: ComboCard,
    val warInfo: WarInfo
)

/**
 * RuleEnv 的默认实现
 * 直接从 warInfo 计算 WarView 快照，不依赖 WarStatus
 */
class WarInfoEnv(private val warInfo: WarInfo) : RuleEnv {
    override fun warView(): WarView = warInfo.toWarView()
}
