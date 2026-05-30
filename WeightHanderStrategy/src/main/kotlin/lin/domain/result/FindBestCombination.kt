package lin.domain.result

import lin.bean.ComboCard
import lin.bean.usePlan.ComboRole
import lin.domain.context.CostWeight

interface FindBestCombination {
    fun findBestCombination(targetList: List<ComboCard>, ableCost: Int): List<ComboCard>
}

object DefaultFindBestCombination : FindBestCombination {
    override fun findBestCombination(targetList: List<ComboCard>, ableCost: Int): List<ComboCard> {
        // 2. 初始化用于寻找最佳组合的变量
        var bestCombination: List<ComboCard> = emptyList()
        // *** 核心改动 ***: 我们追踪的不再是最大权重，而是最大“有效分”
        // 初始化为一个非常小的值，确保任何合法地出牌都比它好
        var maxEffectiveScore = Double.NEGATIVE_INFINITY

        // 3. 定义一个递归函数（回溯）来查找所有可能的组合
        fun findBestCombination(
            startIndex: Int,
            currentCost: Int,
            currentWeight: Double,
            currentCombination: List<ComboCard>
        ) {
            // *** 核心改动 ***
            // 在每次形成一个有效组合时（包括空组合），都计算其“有效分”
            val remainingCost = ableCost - currentCost

            val penalty = remainingCost * CostWeight
            val effectiveScore = currentWeight - penalty

            // 如果当前组合的有效分超过了已知的最高分，则更新最佳组合
            if (effectiveScore > maxEffectiveScore) {
                maxEffectiveScore = effectiveScore
                bestCombination = currentCombination
            }

            // 从 startIndex 开始遍历，继续添加新的牌来探索更深的组合
            for (i in startIndex until targetList.size) {
                val newCard = targetList[i]
                if (newCard.cost() <= remainingCost) {
                    val newComboBonus = evaluateNewComboBonus(currentCombination, newCard)
                    if (newComboBonus.isNaN()) {
                        continue
                    }


                    findBestCombination(
                        startIndex = i + 1,
                        currentCost = currentCost + newCard.cost(),
                        currentWeight = currentWeight + newCard.powerWeight + newComboBonus,
                        currentCombination = currentCombination + newCard
                    )
                }
            }
        }

        // 4. 启动回溯搜索
        // 初始状态是空组合，从索引0开始
        findBestCombination(0, 0, 0.0, emptyList())
        return bestCombination
    }

    /**
     * 只评估“把 newCard 加入 current”这一次增量带来的 combo 影响。
     *
     * - 返回 NaN 表示 coreMutex 硬剪枝：同一 combo 下不同 core 组不能同时进入组合。
     * - 返回普通 Double 表示本次新增的软加权/软惩罚分。
     *
     * 这样避免每个回溯节点重新扫描整个 currentCombination 计算全局 combo 分。
     * 使用 Double 而不是结果对象，是为了减少递归热路径上的短命对象分配。
     */
    private fun evaluateNewComboBonus(current: List<ComboCard>, newCard: ComboCard): Double {
        val newBindings = newCard.comboBindings()
        if (newBindings.isEmpty()) return 0.0

        var scoreBonus = 0.0
        val scoredComboIds = hashSetOf<String>()
        val newMutexCoreBindings = newBindings.filter { it.role == ComboRole.CORE && it.coreMutex }
        val existingCoreBindings = if (newMutexCoreBindings.isEmpty()) {
            emptyList()
        } else {
            current.flatMap { card ->
                card.comboBindings()
                    .filter { it.role == ComboRole.CORE && it.coreMutex }
            }
        }

        for (newBinding in newBindings) {
            if (newBinding in newMutexCoreBindings) {
                val violatesCoreMutex = existingCoreBindings.any { existingBinding ->
                    existingBinding.comboId == newBinding.comboId &&
                            existingBinding.ownGroupId != newBinding.ownGroupId
                }
                if (violatesCoreMutex) return Double.NaN
            }

            if (newBinding.score != 0.0 && scoredComboIds.add(newBinding.comboId)) {
                val hasCounterpart = current.any { it.hasAnyGroup(newBinding.counterpartGroupIds) }
                if (hasCounterpart) scoreBonus += newBinding.score
            }
        }

        return scoreBonus
    }
}
