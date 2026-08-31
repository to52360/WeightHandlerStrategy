package lin.rule.context

import lin.domain.MatchState
import lin.domain.WarInfo

/**
 * 卡属性专用 [RuleEnv]：仅供「谓词组成员判定」使用（T-002）。
 *
 * **为什么需要它**：条件逻辑签名是 `RuleContext.(RuleEnv) -> Boolean`（[lin.rule.condition.ConditionLogic]），
 * 求值必须传一个 RuleEnv。而谓词组成员判定发生在**任意时机**——选牌期、排序期、
 * 甚至 ComboCard 刚构造时——这些位置拿不到真实战场（[lin.rule.context.WarInfoEnv] 依赖 MyWarManage）。
 *
 * **为什么可以这样做**：谓词组**限定只支持卡属性条件**（卡牌类型 / 种族 / 特征位 / 费用），
 * 这类条件只读 `RuleContext.callCard` 上的字段，**根本不访问 RuleEnv**。
 * 故此处只需提供一个「不会被访问」的占位实现。
 *
 * **为什么用抛异常而不是返回空值**：若有人配置了局面类条件（如"手牌里有嘲讽牌"），
 * 返回空值会让判定**静默得出错误结论**（条件恒假 / 恒真），排查极难；
 * 抛异常则是**显式失败**，直接指出配置错误。
 * 这与「谓词组限定卡属性条件」的设计约束配套——违反约束就该当场报错，而不是静默错判。
 *
 * ⚠️ 注意与 [lin.rule.orthogonal.PipelineExecution] 的 `crossCard` 无关：
 * `crossCard` 是**缓存策略开关**（是否跨卡共享分段缓存），本 Env 的 `cache` 恒为不缓存，
 * 不受其影响。
 */
object CardOnlyRuleEnv : RuleEnv {
    private const val HINT =
        "谓词组（条件定义成员的分组）只支持卡属性条件：卡牌类型/种族/特征位/费用。" +
                "当前条件访问了战场环境（%s），而成员判定发生在没有战场的时机（选牌/排序/ComboCard 构造期）。" +
                "请改用卡属性条件，或改用评估树求值路径（那有完整 RuleEnv）。"

    override fun warInfo(): WarInfo = error(HINT.format("warInfo"))

    override fun warView(): WarView = error(HINT.format("warView"))

    override fun matchState(): MatchState = error(HINT.format("matchState"))

    /**
     * 不做缓存：谓词组的管道很短（如 `evaluating_card → to_card → is_card_type`），
     * 缓存收益低于维护成本；且本 Env 是全局单例，缓存会导致跨卡串味。
     */
    override fun <T> cache(key: String, compute: () -> T): T = compute()
}
