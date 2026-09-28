package lin.mcp.diagnostics

import lin.repository.HsCardRepository

/**
 * 配置体检项（**值化扩展点**，`T-FO-019` / `Q-FO-004` 层 1）。
 *
 * ## 为什么是值类（而不是 `interface` + 实现类）
 * 体检项**只有一个动作**（跑一遍、产结果），没有多态层级、没有第二方法要留位、不需要替身注入
 * ⇒ 接口 + 类只是给一个函数套了两层壳。值类把「标识 + 标题 + 动作」一次说清：
 *
 * ```kotlin
 * DiagnosticCheck("surplusFeasibility", "余费门槛可满足性（按生效 N）", ::surplusFeasibilityCheck)
 * ```
 * 加一项体检 = **加一个顶层函数 + 在壳的清单里加一行**（连新文件都不必）。
 * 与项目内 `fun interface StartupTask` 同族：能值化就值化，不预留可能用不到的第二方法
 * （`dependency-inversion-arch`：不过度设计；`D-FO-002`/`D-FO-009`：过程不进容器）。
 *
 * 与 `strategy_coverage` 的分工：**覆盖**答「有没有机制」（结构存在性）；**体检**答「机制对不对」
 * （值级语义一致性、可满足性、引用完整性）。两者面向不同问题，故分成两个只读面。
 *
 * ## 只读 + 只提示
 * 体检**零行为变更、不阻断任何保存**（与 `D-FO-008` 同口径：写侧只能说"可能"，读侧同样只能说"可能"，
 * 但读侧能扫到写侧覆盖不到的多层解析链）。结论必须自带 [DiagnosticResult.caveats] 声明近似边界，
 * 且**必须给出扫描实质**（见 [DiagnosticResult.summary] 的约定）——否则「空结果」无法自证，
 * 等于重演「配置存在当运行生效」的老毛病。
 */
data class DiagnosticCheck(
    /** 输出里的分组键（稳定，供 AI 引用），如 `surplusFeasibility`；也是 `checks` 入参的选择依据。 */
    val id: String,
    /** 人读标题（进输出，不进工具 description）。 */
    val title: String,
    /** 体检动作（壳负责单点 try：坏数据不得拖垮其他项）。 */
    val run: (DiagnosticContext) -> DiagnosticResult
)

/**
 * 一次装配、多项共享的**只读配置快照**（避免每个体检项各自读库 = N 次重复 IO + 各写一份解析）。
 *
 * 装配点在 `StrategyDiagnosticsToolProvider.buildContext()`（单点）；本类只做**读侧近似解析**，
 * 近似边界见 [EffectiveN.basis] 与各体检项的 [DiagnosticResult.caveats]。
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
     * ⚠️ 近似边界（随输出声明）：① 不含**谓词组动态成员**；② 标签层取当前启用卡组的合并结果；
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

/**
 * 单个体检项的结果（`items` 为空 = 未发现该问题）。
 *
 * ⚠️ **`summary` 必须包含「扫描实质」**（不能只报 flagged 数）：至少给出「有多少张卡真的有生效值」与
 * 其来源分布。否则 `items = []` 无法区分「真的干净」与「一个生效值都没读到」——后者会让使用者误信体检通过。
 */
data class DiagnosticResult(
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
