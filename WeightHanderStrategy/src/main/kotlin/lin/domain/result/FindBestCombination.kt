package lin.domain.result

import lin.bean.ComboCard
import lin.domain.context.CostWeight
import lin.domain.context.NotWeight

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
                    //同组加权
                    var comboBonus = NotWeight
                    //todo-future  默认无环形结构,无法处理环形结构
                    newCard.combo?.also {
                        currentCombination.forEach {
                            comboBonus += newCard.comboAddWeight(it)
                        }
                    } ?: run {
                        currentCombination.forEach { existingCard ->
                            // combo加权
                            comboBonus += existingCard.comboAddWeight(newCard)
                        }
                    }

                    findBestCombination(
                        startIndex = i + 1,
                        currentCost = currentCost + newCard.cost(),
                        currentWeight = currentWeight + newCard.powerWeight + comboBonus,
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
}