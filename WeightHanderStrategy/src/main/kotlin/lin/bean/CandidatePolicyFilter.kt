package lin.bean

import lin.bean.cardExt.base.isMinion
import lin.domain.context.NotWeight
import lin.domain.context.TacticalScoreScale

/**
 * 候选门控过滤（T-008，D-003；T-026 枚举退役后 (N, ts) 二元组模型）：
 *
 * 候选资格决定一张牌「能否进入哪一轮」，与 `UseStage`（排序）/`replanAfterUse`（执行生命周期）互不推导。
 * 三态候选策略枚举已被 (N, ts) 完全吸收（Q-026 方案 C + T-027）：
 * - **第一轮**（主组合竞争）：ts ≠ 0（有战术立场，**含 ts<0 降权竞争入口**）或 N == 0 才进；ts == 0 且 N > 0 则惜售。
 * - **第二轮**（余费填充）：仅 ts > 0（兑现）绕 N 优先填充；ts ≤ 0（含亏模）尊重 N（余费门槛=惜售）。
 * - 极端 -100 = 绝对不打，走 isUnUse 硬禁，候选链前即被抽离，本门不涉及。
 * 第二轮余费门控（D-007 后双通道，正交互补）：
 *   ① [passesSecondRoundCandidate]（powerWeight > 0）= **动态亏模兜底**——评估树/规则给负分（这局面打出去亏）
 *     的牌挡在余费外，兼 unUse 防死循环；
 *   ② [passesSurplusGate]（仅 ts>0 绕 N；否则看空闲 ≥ 牌费 + N）= **静态持有意愿 + 亏模不插队**——无战术立场(ts==0)
 *     或战术未满足(ts<0)都尊重 N 惜售（D-007 + D-012），ts<0 的降权只作用第一轮竞争，不优先填充。
 *
 * 注意：候选门控只做「进不进候选」，不调用 `unUse()`/Banned——那是 EvalOutcome.Banned 硬禁语义（永久禁出），
 * 与本软门控（暂时不出）互不重叠。
 */
fun ComboCard.passesFirstRoundCandidate(): Boolean =
    tacticalScore != 0.0 || surplusIdleThreshold() == 0

/** 第二轮余费候选资格：费用门槛由调用方（fillSurplusCost）按 cost <= remainingCost 控制。 */
fun ComboCard.passesSecondRoundCandidate(): Boolean =
    powerWeight > NotWeight

/**
 * 余费候选完整判定（T-011 fillSurplusCost / T-013 compensateFailedCards 共享的集中变化点）：
 * 费用门槛（cost <= remainingCost）+ 第二轮候选资格（[passesSecondRoundCandidate]）+ 余费门槛（D-007/D-012）+ 满场随从排除。
 * 战场满时不出随从，与执行层 UseFunction 硬拦截语义一致。
 * D-011：nDelta（全局绝望门槛减量，[lin.domain.surplusDespairNDelta]）按血量阶梯降低 N——不豁免负分亏模与满场排除。
 */
fun ComboCard.passesSurplusCandidate(
    remainingCost: Int,
    isFull: Boolean,
    nDelta: Int = 0
): Boolean =
    cost() <= remainingCost && passesSecondRoundCandidate() && passesSurplusGate(remainingCost, nDelta)
            && !(isFull && isMinion())

// ====================================================================
// D-007 双费数模型 v3（T-015）+ D-012 门槛语义 v2：等效费 E（D-014 原语义，卡牌固有等效费，不含战术溢价）
// + 垫后余量门槛 N（放行 ⟺ 空闲 ≥ 牌费 + N，即「垫出后空闲池仍须剩 N 费」）+ 战术溢价（树分×scale，费单位）。
// 门与价值解耦：N 管「垫后还须剩几费」，E+溢价管「垫进去值多少」；无 G 中间量（v2 的 G/fallback 派生已删）。
// ====================================================================

/**
 * 等效费 E（D-014 语义：卡牌固有等效费用，不含战术溢价）：配置等效费（解码后整数）> 随从实时身材 (atc+hp)/2
 * > 法术实时费。phase-1 近似：法术用实时费（D-014 baseValue 走数据库初始费保守线，缓存属 MyWarManage 不可达）。
 */
fun ComboCard.equivalentCostValue(): Double {
    val configured = cardWeightInfo?.powerWeight ?: 0.0
    if (configured > 0.0) return configured
    return if (card.isMinion()) (card.atc + card.health) / 2.0 else cost().toDouble()
}

/**
 * 垫后余量门槛 N（D-012 语义 v2）：放行 ⟺ 空闲 ≥ 牌费 + N——N 统一表示「垫出这张牌后，空闲池还须剩 N 费」，
 * 跨卡费同义、无需与牌费心算比较（v1 池语义「空闲 ≥ max(牌费,N)」对 N ≤ 牌费的卡静默空设，已废弃）。
 * 未配置 = 0（付得起即垫，D-005「无战术不死捏」——持有意愿一律显式配置）。
 * 解析链（T-019 + T-026 插第三层）：逐卡小数位（[CardWeightInfo.surplusIdleThreshold]）> 分组行为
 * （[CardCombinedConfig.groupSurplusIdleThreshold]）> tag 默认（[UseIntent.tagDefaultSurplusIdleThreshold]，用途标签
 * 预设 N，T-026 替代原 candidatePolicy 预设）> 0。分组级解决「一类牌统一捏、不用逐卡设置」
 * （如解牌组统一 N=2 = 垫出后仍剩 2 费才肯垫）。
 */
fun ComboCard.surplusIdleThreshold(): Int =
    cardWeightInfo?.surplusIdleThreshold
        ?: combinedConfig?.groupSurplusIdleThreshold
        ?: useIntent()?.tagDefaultSurplusIdleThreshold
        ?: 0

/**
 * fillValue（填充交付费值）= 等效费 E + 战术溢价（tacticalScore×[TacticalScoreScale]）。
 * ts==0 → E；ts>0 → 更高溢价（战术命中且此刻更值得垫）；ts<0（非 -100 绝对禁）→ 折价补位
 * （更低的临界值，只在不浪费剩余费时垫出）。scale 为分→费换算（树量纲费化后可退役，Q-024）。
 * T-027（Q-026 §2.1）：删 `max`，负分不再抹平——ts<0 以真实折价参与填充，防 [SurplusFillCombination] 的 `>0.0`
 * 软死捏过滤被击穿（极端负分 → fillValue ≤ 0 被自动剔除）。
 */
fun ComboCard.surplusFillValue(): Double =
    equivalentCostValue() + tacticalScore * TacticalScoreScale

/**
 * 余费门槛（D-007 + D-012 垫后余量语义）：战术兑现（tacticalScore > 0）= 此刻价值兑现，直接放行（**优先填充**）；
 * 否则（含 ts<0 亏模、ts==0 无立场）空闲 ≥ 牌费 + N 才放行（N=「垫出后仍须剩 N 费」；「不贪心」= 直接配更小的 N）。
 * D-011：nDelta（绝望门槛减量）降低 N，地板 0（付得起即垫；D-005「无普遍死捏」地板由未配置=0 表达）。
 * T-027（Q-026 §2.4）：命中=ts≠0 仅作用于**第一轮**（降权竞争入口）；**填充期只认 ts>0（兑现）绕 N**——
 * ts<0（战术未满足=亏模）不优先填充：尊重 N（余费门槛=惜售），N>0 则 held，除非空闲 ≥ cost+N 或绝望松门；
 * 「余费门槛 + 减权重」并存牌的降权只影响第一轮竞争，不能让它插队填充。绝对不打（-100）走 isUnUse 硬禁。
 */
fun ComboCard.passesSurplusGate(idleCost: Int, nDelta: Int = 0): Boolean =
    tacticalScore > 0.0 || idleCost >= cost() + (surplusIdleThreshold() - nDelta).coerceAtLeast(0)
