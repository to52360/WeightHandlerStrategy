package lin.domain

import lin.bean.surplusIdleThreshold
import lin.config.EngineConfig
import lin.rule.context.toWarView

/**
 * D-011 全局绝望规则 v2（Q-022 收敛）：布尔前置 × 血量阶梯幅度。
 *
 * 语义：余费门槛 N 的存在意义是保未来战术收益。当**前置**成立——场面承压（溢出伤害为正且超过
 * 当前血量的可容忍攻击力，[lin.rule.context.WarView] 的 excessDamage/ableAtcSum，无状态现算）
 * 且手牌已无 N>0 惜售牌（T-026 迁移：原「无 TACTICS_DOMINANT」→ 未来配合不可能，含不可负担——
 * 它们仍是未来希望，故查 handComboCards 非 canUseCards）——持有机会成本随血量下降单调上升，按**血量阶梯**
 * 返回门槛减量 nDelta（门控 [lin.bean.passesSurplusGate] 按 N − nDelta 放行，地板 0 不破 D-012）。
 *
 * - 阶梯配置 `scoring.surplus.despair.ladder`，格式 `blood:delta` 逗号分隔（如 `15:1,10:2,5:9`），
 *   语义 = 血量 < blood 时该档 delta 生效，多档命中取最大；非法片段忽略（warn 日志零、静默跳过，
 *   与 ConfigStore 宽松风格一致）。二值绝望（某档 delta=9）是阶梯的退化特例。
 * - 费域整数对整数，无分→费量纲桥（D-011 原否决理由限定于 ScoreEffect 树内桥接，配置阶梯不涉）。
 * - 不豁免：Banned（isUnUse 硬禁，候选门挡）、满场随从——绝望只松第二轮 N 门槛，不动资格轴；
 *   负分亏模的判断已移交填充搜索层 FILL_VALUE_FLOOR（Q-036/D-020），nDelta 不与之交互
 *   （垫出一个 fillValue 非正的牌 = 主动亏模，依旧不可能）。
 * - 无状态：调用点（fillSurplusCost 前 / compensateFailedCards）现算，一次一判。
 * - enabled/ladder 默认参数仅为单测注入（ConfigStore 启动期加载一次，运行期不可变）。
 * - 待议子项（用户暂未想好，勿擅自实施）：① 阶梯的 UI/MCP 自由配置入口；② 分组级「无配合条件」
 *   的自定义定义（当前全局口径 = 无 N>0 惜售牌；分组口径属 Phase-2 分层组合）。
 */
fun WarInfo.surplusDespairNDelta(
    enabled: Boolean = EngineConfig.surplusDespairEnabled,
    ladder: String = EngineConfig.surplusDespairLadder
): Int {
    if (!enabled) return 0
    val view = toWarView()
    // 前置①场面承压：溢出伤害为正（空场/嘲讽全承 = 无压力，不绝望）且 ≥ 当前血量容忍度
    if (!(view.excessDamage > 0 && view.excessDamage >= view.ableAtcSum)) return 0
    // 前置②未来配合不可能：手牌无 N>0 惜售牌（配 N = 声明未来收益，有声明即未来希望）
    if (handComboCards.any { it.surplusIdleThreshold() > 0 }) return 0
    // 幅度：血量阶梯（多档命中取最大）
    val blood = view.meBlood
    return parseDespairLadder(ladder)
        .filter { blood < it.first }
        .maxOfOrNull { it.second }
        ?: 0
}

/** 解析阶梯配置 `blood:delta,...`：blood>0、delta>0 才有效，非法片段静默忽略。 */
internal fun parseDespairLadder(raw: String): List<Pair<Int, Int>> =
    raw.split(',')
        .mapNotNull { tier ->
            val parts = tier.trim().split(':')
            if (parts.size != 2) return@mapNotNull null
            val blood = parts[0].trim().toIntOrNull()
            val delta = parts[1].trim().toIntOrNull()
            if (blood != null && blood > 0 && delta != null && delta > 0) blood to delta else null
        }
