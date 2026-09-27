package lin.repository.card_group

import lin.repository.HsCardRepository

/**
 * 惜售门槛 N 的**写入侧可满足性提示**（T-FO-018 / K-FO-011）。
 *
 * 事实：放行条件 = 「空闲 ≥ 牌费 + N」（引擎 `passesSurplusGate`），而空闲上限 = 对局法力上限
 * [MANA_CAP] ⇒ `牌费 + N > 10` 的牌在「未命中战术（ts = 0）且非 AGGRESSIVE」时垫不出。
 *
 * ## ⚠️ 为什么只能「提示」而不能「拒收」（2026-09-27 用户指正后修正）
 * 本类只能拿到 `hs.cards` 的**数据库初始费**，而引擎判定用的是 `card.cost()`（**实时费**）：
 * 减费机制下 `实时费 < 初始费`（罕见加费机制下反之）。故：
 * - `初始费 + N ≤ 10` ⇒ 实时费只会更低 ⇒ **必然可满足**（判据的充分方向成立，无提示）；
 * - `初始费 + N > 10` ⇒ 实时费可能更低 ⇒ **只是"可能不可满足"**，是**必要非充分**条件。
 * ⇒ 误报代价（拒收合法配置）不可接受，故**只提示、不阻断**；是否真死锁由使用者结合该卡是否有减费机制判断。
 *
 * ## 设计取舍（D-FO-008）
 * - 提示放**写侧**（保存时提示），不放引擎侧 —— 引擎自动钳 N 等于静默改写用户声明，比死锁更难排查。
 * - 本类是该校验的**单点**：凡能确定「牌集合 + 本次写入的 N」的写入口都调它，不得各自实现一遍。
 * - **责任边界（有意收窄）**：只覆盖能确定牌集合的通道（逐卡 / 分组级）。
 *   「生效 N 由多层解析链决定」的通道（标签预设 N → 打标继承、预设维度 → 卡组引用）不在本类范围，
 *   由 `T-FO-001` 配置语义体检能力覆盖（剩余接入点见 K-FO-011）。
 * - **查不到费不提示**：`hs.cards` 缺记录（本库未收录的卡）时跳过，不因元数据缺失打扰使用者。
 */
class SurplusGateValidator(private val cardRepo: HsCardRepository) {

    companion object {
        /** 对局法力上限（标准 10 水晶）。超 10 水晶的对局/构筑不适用本判据（判据只作写侧提示，不参与评分）。 */
        const val MANA_CAP = 10

        /** 纯判据：按给定费与 N 是否可满足。 */
        fun isFeasible(cost: Int, n: Int): Boolean = cost + n <= MANA_CAP
    }

    /**
     * 逐张产出「按初始费看可能垫不出」的提示。
     *
     * @param cardIds 受该 N 影响的牌（逐卡通道 = 单张；分组通道 = 组内成员；谓词组 cardIds 为空 ⇒ 本校验不适用）
     * @param n 本次写入的门槛；null / ≤ 0（无门槛）⇒ 恒无提示
     * @param scope 提示里的来源描述（如「分组 守卫组」「逐卡声明」），便于定位
     * @return 提示列表（空 = 无提示）；每条含卡名 / 初始费 / N / 费+N，并注明「费用可变的卡请自行确认」
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
            else "「$scope」$cardId（${detail.name ?: "未知名"}）：数据库初始费 $cost + 门槛 N $threshold = " +
                    "${cost + threshold} > 法力上限 $MANA_CAP ⇒ 若该卡费用不会低于初始费，则未命中战术时永远垫不出" +
                    "（仅战术命中 / 绝望规则放行）；**费用可变的卡（减费）实时费可能更低 ⇒ 可能实际可满足，请自行确认**。"
        }
    }
}
