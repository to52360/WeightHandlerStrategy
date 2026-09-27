package lin.repository.card_group

import lin.repository.HsCardRepository

/**
 * 惜售门槛 N 的**写入侧可满足性校验**（T-FO-018 / K-FO-011）。
 *
 * 事实：放行条件 = 「空闲 ≥ 牌费 + N」（引擎 `passesSurplusGate`），而空闲上限 = 对局法力上限
 * [MANA_CAP] ⇒ `牌费 + N > 10` 的牌在「未命中战术（ts = 0）且非 AGGRESSIVE」时
 * **永远不可能被垫出**——引擎既不报错也不警告，是静默的事实硬禁。
 *
 * 设计取舍（D-FO-008）：
 * - 校验放**写侧**（保存时拒收），不放引擎侧 —— 引擎自动钳 N 等于静默改写用户声明，比死锁更难排查。
 * - 本类是该校验的**单点**：凡能确定「牌集合 + 本次写入的 N」的写入口都必须调它，
 *   不得各自实现一遍（同一事实两套实现必然漂移）。
 * - **责任边界（有意收窄）**：只覆盖能确定牌集合的通道（逐卡 / 分组级）。
 *   「生效 N 由多层解析链决定」的通道（标签预设 N → 打标继承、预设维度 → 卡组引用）不在本类范围，
 *   由 `T-FO-001` 配置语义体检能力覆盖（剩余接入点见 K-FO-011）。
 * - **查不到费不阻断**：`hs.cards` 缺记录（本库未收录的卡）时跳过该卡，不因元数据缺失拒绝合法配置。
 */
class SurplusGateValidator(private val cardRepo: HsCardRepository) {

    companion object {
        /** 对局法力上限（标准 10 水晶）。超 10 水晶的对局/构筑不适用本判据（判据只作写侧提醒，不参与评分）。 */
        const val MANA_CAP = 10

        /** 纯判据：该费 + N 可满足 ⟺ 不超过法力上限。 */
        fun isFeasible(cost: Int, n: Int): Boolean = cost + n <= MANA_CAP
    }

    /**
     * 逐张核对 `牌费 + N ≤ MANA_CAP`。
     *
     * @param cardIds 受该 N 影响的牌（逐卡通道 = 单张；分组通道 = 组内成员；谓词组 cardIds 为空 ⇒ 本校验不适用）
     * @param n 本次写入的门槛；null / ≤ 0（无门槛）⇒ 恒通过
     * @param scope 违规消息里的来源描述（如「分组 守卫组」「逐卡声明」），便于定位
     * @return 违规描述列表（空 = 通过）；每条含卡名 / 牌费 / N / 费+N
     */
    fun violations(cardIds: Collection<String>, n: Int?, scope: String): List<String> {
        val threshold = n ?: return emptyList()
        if (threshold <= 0 || cardIds.isEmpty()) return emptyList()
        val distinctIds = cardIds.distinct()
        val details = cardRepo.findCardDetailsByIds(distinctIds).associateBy { it.cardId }
        return distinctIds.mapNotNull { cardId ->
            val detail = details[cardId] ?: return@mapNotNull null
            val cost = detail.cost ?: return@mapNotNull null
            if (isFeasible(cost, threshold)) null
            else "「$scope」$cardId（${detail.name ?: "未知名"}）：牌费 $cost + 门槛 N $threshold = " +
                    "${cost + threshold} > 法力上限 $MANA_CAP ⇒ 该牌平时永远垫不出（仅战术命中 / 绝望规则放行）"
        }
    }
}
