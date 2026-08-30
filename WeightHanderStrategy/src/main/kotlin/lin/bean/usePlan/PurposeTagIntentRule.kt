package lin.bean.usePlan

/**
 * 用途标签默认意图规则。
 *
 * 描述每个 [PurposeTagId] 在不被 card/group override 覆盖时的默认出牌意图字段。
 * [UseIntentDeriver] 按 priority 降序匹配标签，决定默认 [UseStage] 等。
 *
 * 本层是**全局兜底**语义（T-036）：表达「某一类用途的牌，普遍应该如何」的跨卡组经验，
 * 单卡通过打标签继承，而非在卡级复制一份编排配置。
 *
 * @param tagId 用途标签
 * @param defaultStage 该标签默认映射的出牌阶段
 * @param defaultOrderWeight 默认排序权重
 * @param priority 优先级（数值越大越优先匹配）
 * @param defaultReplanAfterUse 该标签声明「打出后必须重新评估」（全局兜底，T-036 接线）。
 *   必要性：`WarInfo.isChangeByUseSuccess` 靠**手牌数量**启发式推断状态变化——
 *   过牌（打出 1 抽 1，手牌数不减）能碰巧识别；**清场牌只减手牌、改变的是战场 → 识别不到**
 *   → 打完解牌不 replan，后续牌按过时战场信息决策。故此类语义必须显式声明，不能依赖启发式。
 *   合并语义：与卡级/分组级声明取 **OR**（任一为真即 replan）——多评估一次安全，漏评估会错。
 * @param defaultSurplusIdleThreshold 默认余费门槛 N（T-026，替代原 candidatePolicy 预设）；null = 未声明，回落 0。
 *   语义 = 声明「此牌未来有更大收益，平时惜售」：战术命中（ts>0）兑现即放行，未命中时需空闲 ≥ 牌费+N 才垫。
 *   多标签命中取 max（有战术身份即惜售，保守方向），见 [lin.domain.use.plan.UseIntentDeriver]。
 */
data class PurposeTagIntentRule(
    val tagId: PurposeTagId,
    val defaultStage: UseStage,
    val defaultOrderWeight: Double = 0.0,
    val priority: Int = 100,
    val defaultReplanAfterUse: Boolean = false,
    val defaultSurplusIdleThreshold: Int? = null
)

/**
 * 用途标签意图规则的数据源接口。
 *
 * 解耦规则来源，方便后续切换为数据库、SPI、配置文件等实现。
 */
interface PurposeTagIntentRuleProvider {
    fun rules(): List<PurposeTagIntentRule>
}

/**
 * 默认规则提供者：硬编码的 7 条基础规则。
 *
 * 作为 [PurposeTagIntentRuleProvider] 的 fallback 实现；
 * 后续可替换为数据库驱动或其他外部配置加载实现。
 *
 * defaultSurplusIdleThreshold 映射（T-026 替代原 candidatePolicy 预设；Q-033 复核收窄，2026-08-30）：
 * - N=1 **仅保留给「晚出可能更有价值」的标签**：`SAVE_LIFE`（后面可能血量危险）、`CLEAN`（后面可能有更大威胁）。
 * - 其余标签 → null（N=0 全自由）。
 *
 * **收窄理由**：N 的真实语义是「留到后面可能更有价值」，这是**局面属性**，不普遍属于用途：
 * ①`GREED` 成长牌早出才有价值，晚出就废了，且 N=1 会把它挡在第一轮主组合外
 *   （`passesFirstRoundCandidate()`：`ts != 0 || N == 0`），使其 `stage=SETUP` 的排序位置成为空设；
 * ②`DRAW_CARD` 早过牌早赚，惜售与过牌目的相反；
 * ③与 Q-033 对 `FINISH` 的定性同构——「该不该留着」与「这回合能不能斩杀」都依赖场面，
 *   应由评估树/战术分表达，而非标签一刀切。
 *
 * ⚠️ 注意耦合：N>0 且 tacticalScore==0 的牌不进第一轮主组合（`WeightResult` 过滤），
 * 只能等余费填充。故 N=1 会连带使该标签的 `defaultStage` 排序在「未配评估树」时**实际不生效**。
 *
 * 预设值先设后实战校准（Q-026 拍板②）；多标签命中取 max（见意图推导器），无冲突异常。
 */
class DefaultPurposeTagIntentRuleProvider : PurposeTagIntentRuleProvider {
    /** 共 6 条（FINISH 无条目，见下方注释）。 */
    override fun rules(): List<PurposeTagIntentRule> = listOf(
        PurposeTagIntentRule(
            tagId = PurposeTagId.SAVE_LIFE,
            defaultStage = UseStage.LATE,
            priority = 400,
            defaultSurplusIdleThreshold = 1
        ),
        // T-030（D-017）：defaultOrderWeight 首次启用——解牌先于过牌。
        // CLEAN=1 > DRAW_CARD=0：手牌已有解牌且值得出 → 解牌先出；
        // 解牌不行/无解牌 → 过牌先出（同在 MID 段，先于 LATE/GENERAL），抽到新牌后经 shouldReplan 重规划。
        PurposeTagIntentRule(
            tagId = PurposeTagId.CLEAN,
            defaultStage = UseStage.MID,
            priority = 300,
            defaultOrderWeight = 1.0,
            defaultSurplusIdleThreshold = 1
        ),
        // FINISH 无规则条目（Q-033 收口）：斩杀是**局面属性**而非卡牌固有用途——
        // 火球术是解场还是斩杀取决于场面，不取决于这张牌。
        // 故 FINISH 不再承担编排映射：阶段回落 GENERAL，惜售交给评估树/战术分表达（D-016「何时斩杀是战术决策」）。
        // FINISH 仍作为**查询标签**保留（PurposeTagId / PurposeTagProvider），供
        // 正交管道 purpose_filter(FINISH) 与评估树 PURPOSE_TAG 绑定使用——即用途标签的原始设计意图。
        // Q-033 复核：N 由 1 改 null。成长牌**早出才有价值**，晚出就废了；
        // 且 N=1 会把它挡在第一轮主组合外，使 stage=SETUP 的排序位置形同空设。
        PurposeTagIntentRule(
            tagId = PurposeTagId.GREED,
            defaultStage = UseStage.SETUP,
            priority = 100,
            defaultSurplusIdleThreshold = null
        ),
        PurposeTagIntentRule(
            tagId = PurposeTagId.VALUE,
            defaultStage = UseStage.GENERAL,
            priority = 50,
            defaultSurplusIdleThreshold = null
        ),
        PurposeTagIntentRule(
            tagId = PurposeTagId.EXTRA_COST,
            defaultStage = UseStage.GENERAL,
            priority = 50,
            defaultSurplusIdleThreshold = null
        ),
        // T-030（D-017）：DRAW_CARD 从 GENERAL 迁到 MID——与解牌同段，靠 defaultOrderWeight 分先后
        // （CLEAN=1 > DRAW_CARD=0，见上）。过牌仍先于 LATE/GENERAL；个别卡组可用 stageOverride 拉回。
        // Q-033 复核：N 由 1 改 null。早过牌早赚，惜售与过牌目的相反。
        PurposeTagIntentRule(
            tagId = PurposeTagId.DRAW_CARD,
            defaultStage = UseStage.MID,
            priority = 60,
            defaultOrderWeight = 0.0,
            defaultSurplusIdleThreshold = null
        )
    )
}
