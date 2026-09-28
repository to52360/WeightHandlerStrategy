package lin.mcp.diagnostics

import lin.repository.HsCardRepository

/**
 * 配置体检项（**值化扩展点**，T-FO-019 / Q-FO-004 层 1）。
 *
 * 与 `strategy_coverage` 的分工：**覆盖**答「有没有机制」（结构存在性）；**体检**答「机制对不对」
 * （值级语义一致性、可满足性、引用完整性）。两者面向不同问题，故分成两个只读面，不混在一个工具里。
 *
 * ## 扩展方式（本设计的全部价值）
 * 加一项体检 = **加一个 [DiagnosticCheck] 实现 + 在 `StrategyDiagnosticsToolProvider` 的清单里加一行**。
 * 不必改工具壳、不必改输出组装、不必碰其他体检项。
 * （对比 `StrategyCoverageToolProvider`：加一类覆盖语义要同时改 5 处 —— 装配索引 / 分组视图 / 状态判定 /
 * summary 计数 / description；该类的迁移方案见 `cross-dialogue/策略覆盖与诊断扩展-方案.md`。）
 *
 * ## 只读 + 只提示
 * 体检**零行为变更、不阻断任何保存**（与 `D-FO-008` 同口径：写侧只能说"可能"，读侧同样只能说"可能"，
 * 但读侧能扫到写侧覆盖不到的多层解析链）。输出必须自带 `basis`（生效来源）与 [caveats] 近似边界。
 */
interface DiagnosticCheck {

    /** 输出里的分组键（稳定，供 AI 引用），如 `surplusFeasibility`。 */
    val id: String

    /** 人读标题（进输出，不进工具 description）。 */
    val title: String

    /** 执行体检（坏数据不得抛异常中断其他项——壳负责单点 try）。 */
    fun run(ctx: DiagnosticContext): DiagnosticResult
}

/**
 * 一次装配、多项共享的**只读配置快照**（避免每个体检项各自读库 = N 次重复 IO + 各写一份解析）。
 *
 * 装配点在 `StrategyDiagnosticsToolProvider.buildContext()`（单点）；本类只做**读侧近似解析**，
 * 近似边界见 [EffectiveN.basis] 与各 check 的 [DiagnosticResult.caveats]。
 */
class DiagnosticContext(
    /** 参与体检的卡组范围。 */
    val managers: List<ManagerScope>,
    /** 逐卡层 N（`.cardgroup` 的 `powerWeight` 解码；0 = 未声明）。 */
    val poolNByCard: Map<String, Int>,
    /** 分组层 N（**静态**组成员展开；谓词组 cardIds 为空 ⇒ 不覆盖）。 */
    val bindingNByCard: Map<String, Int>,
    /** 分组层来源的分组名（诊断用）。 */
    val bindingNameByCard: Map<String, String>,
    /** 标签层 N（当前启用卡组上下文的合并规则；多标签取 max）。 */
    val tagNByCard: Map<String, Int>,
    /** 卡的标签集合（诊断用）。 */
    val tagsByCard: Map<String, List<String>>,
    val cardRepo: HsCardRepository
) {

    /**
     * 生效余费门槛 N 的读侧解析（**逐卡 > 分组 > 标签 > 0**，与引擎 `ComboCard.resolveIdleThreshold()` 同链）。
     *
     * ⚠️ 近似边界（已随输出声明）：① 不含**谓词组动态成员**；② 标签层取当前启用卡组的合并结果；
     * ③ 判可满足性用**数据库初始费**（引擎用实时费，减费卡可能实际可满足 ⇒ 只提示）。
     */
    fun effectiveN(cardId: String): EffectiveN {
        poolNByCard[cardId]?.takeIf { it > 0 }?.let { return EffectiveN(it, "逐卡声明") }
        bindingNByCard[cardId]?.takeIf { it > 0 }?.let {
            return EffectiveN(it, "分组「${bindingNameByCard[cardId] ?: "?"}」")
        }
        tagNByCard[cardId]?.takeIf { it > 0 }?.let {
            return EffectiveN(it, "标签 ${tagsByCard[cardId].orEmpty().joinToString("/")}")
        }
        return EffectiveN(0, "未声明")
    }

    /** 参与体检的卡 id（各卡组卡池 ∪ 组内静态成员，去重保序）。 */
    fun cardIdsInScope(): List<String> = managers.flatMap { it.cardIds }.distinct()
}

/** 生效门槛 + 其来源（`basis` 让输出可解释，避免"这个 N 是哪来的"再次需要人工反推）。 */
data class EffectiveN(val value: Int, val basis: String)

/** 单个体检项的结果（items 为空 = 通过）。 */
data class DiagnosticResult(
    val id: String,
    val title: String,
    val summary: Map<String, Any?>,
    val items: List<Map<String, Any?>>,
    /** 本项的近似边界声明（原样进输出，供 AI/人判断可信度）。 */
    val caveats: List<String> = emptyList()
)

/** 体检范围内的一个卡组。 */
data class ManagerScope(
    val managerId: String,
    val managerName: String,
    val sourceFile: String,
    /** 该卡组涉及的卡（卡池 ∪ 组内静态成员）。 */
    val cardIds: List<String>
)
