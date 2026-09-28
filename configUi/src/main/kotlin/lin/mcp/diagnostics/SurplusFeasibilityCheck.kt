package lin.mcp.diagnostics

import lin.repository.card_group.SurplusGateValidator

/**
 * 体检项①：**生效余费门槛可满足性**（`T-FO-019` 第一刀 / `Q-FO-004` 层 1 的候选 A）。
 *
 * 查什么：每张卡**最终生效的 N**（走完 逐卡 → 分组 → 标签 解析链，见 [DiagnosticContext.effectiveN]）
 * 是否满足 `数据库初始费 + N ≤ 法力上限`。不满足 ⇒ 该牌未命中战术时垫不出（可能永远打不出）。
 *
 * 为什么放读侧（而不是继续加写侧校验）：`K-FO-011` / `T-FO-018` 的剩余通道（标签预设 N → 打标继承、
 * 预设维度 → 卡组引用、UI 表单入口）**生效值由多层解析链决定**，写侧拿不到最终值；读侧一次扫完。
 *
 * **只提示不阻断**：判据用数据库**初始费**，引擎用**实时费**（减费卡可能实际可满足）⇒ [caveats] 声明。
 */
class SurplusFeasibilityCheck : DiagnosticCheck {

    override val id: String = "surplusFeasibility"
    override val title: String = "余费门槛可满足性（按生效 N）"

    override fun run(ctx: DiagnosticContext): DiagnosticResult {
        val cardIds = ctx.cardIdsInScope()
        val details = ctx.cardRepo.findCardDetailsByIds(cardIds).associateBy { it.cardId }
        val items = mutableListOf<Map<String, Any?>>()

        for (cardId in cardIds) {
            val effective = ctx.effectiveN(cardId)
            if (effective.value <= 0) continue
            val detail = details[cardId] ?: continue
            val cost = detail.cost ?: continue
            if (SurplusGateValidator.isFeasible(cost, effective.value)) continue
            items += mapOf(
                "cardId" to cardId,
                "name" to (detail.name ?: ""),
                "dataBaseCost" to cost,
                "effectiveN" to effective.value,
                "effectiveBasis" to effective.basis,
                "message" to "初始费 $cost + 生效 N ${effective.value} = ${cost + effective.value} > " +
                        "法力上限 ${SurplusGateValidator.MANA_CAP} ⇒ 若该卡费用不会低于初始费，" +
                        "则未命中战术时永远垫不出；费用可变的卡（减费）请自行确认"
            )
        }

        return DiagnosticResult(
            id = id,
            title = title,
            summary = mapOf(
                "scannedCards" to cardIds.size,
                "flaggedCards" to items.size,
                "managers" to ctx.managers.map { it.managerName }
            ),
            items = items,
            caveats = listOf(
                "判据用数据库初始费，引擎判定用实时费（减费卡实际可能可满足）⇒ 本条只作提示。",
                "不含谓词组动态成员（运行时才定）；分组层只覆盖静态组成员。",
                "标签层取当前启用卡组上下文的合并结果（预设 + 卡组增量项）；其他卡组的标签层 N 不参与。"
            )
        )
    }
}
