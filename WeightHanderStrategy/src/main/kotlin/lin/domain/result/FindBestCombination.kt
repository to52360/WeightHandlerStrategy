package lin.domain.result

import lin.bean.ComboCard
import lin.bean.usePlan.ComboPlanDefinition
import lin.bean.usePlan.TodoComboPlanDefinitionProvider
import lin.domain.context.CostWeight
import lin.domain.context.NotWeight

interface FindBestCombination {
    fun findBestCombination(targetList: List<ComboCard>, ableCost: Int): List<ComboCard>
}

object DefaultFindBestCombination : FindBestCombination {
    override fun findBestCombination(targetList: List<ComboCard>, ableCost: Int): List<ComboCard> {
        // 安全读取动态 Combo 定义以防 TODO 抛出 NotImplementedError
        val definitions = try {
            TodoComboPlanDefinitionProvider.findAll()
        } catch (e: NotImplementedError) {
            emptyList()
        }

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
            // 加上动态全局 Combo 的加分
            val dynamicComboBonus = calculateComboBonus(currentCombination, definitions)
            val effectiveScore = currentWeight + dynamicComboBonus - penalty

            // 如果当前组合的有效分超过了已知的最高分，则更新最佳组合
            if (effectiveScore > maxEffectiveScore) {
                maxEffectiveScore = effectiveScore
                bestCombination = currentCombination
            }

            // 从 startIndex 开始遍历，继续添加新的牌来探索更深的组合
            for (i in startIndex until targetList.size) {
                val newCard = targetList[i]
                if (newCard.cost() <= remainingCost) {
                    // 👈 核心排斥剪枝：如果触发了 coreMutex 互斥限制，则直接跳过
                    if (violatesCoreMutex(currentCombination, newCard, definitions)) {
                        continue
                    }

                    // 同组加权 (legacy 逻辑)
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

    /**
     * 判断当前组合加入新卡牌后是否违反任何动态 Combo 的 coreMutex 规则
     */
    private fun violatesCoreMutex(
        current: List<ComboCard>,
        newCard: ComboCard,
        definitions: List<ComboPlanDefinition>
    ): Boolean {
        val newGroups = newCard.groupIds()
        for (def in definitions) {
            if (!def.coreMutex) continue

            // 直接判断 newCard 是否属于该 combo 的核心组之一，避免 filter 创建新 List
            if (!newGroups.any { it in def.coreGroupIds }) continue

            // 遍历已选卡牌，直接用短路布尔匹配，完全避免 flatMap、filter 和 Set 的内存开销
            for (existingCard in current) {
                val existingGroups = existingCard.groupIds()
                // 判断已选卡牌是否属于该 combo 核心组
                val existingHitsCore = existingGroups.any { it in def.coreGroupIds }
                if (existingHitsCore) {
                    // 如果已选卡牌和 newCard 在核心组上没有共同的核心组 ID，代表横跨了不同的核心组，冲突！
                    val sharesCoreGroup = newGroups.any { it in def.coreGroupIds && it in existingGroups }
                    if (!sharesCoreGroup) {
                        return true
                    }
                }
            }
        }
        return false
    }

    /**
     * 计算当前组合触发的动态 Combo 加分
     */
    private fun calculateComboBonus(
        combination: List<ComboCard>,
        definitions: List<ComboPlanDefinition>
    ): Double {
        var totalBonus = 0.0
        val allCardGroups = combination.flatMap { it.groupIds() }.toSet()
        for (def in definitions) {
            // 判定条件：核心组有命中，且依赖组有命中
            val hasCore = def.coreGroupIds.any { it in allCardGroups }
            val hasDep = def.depGroupIds.any { it in allCardGroups }
            if (hasCore && hasDep) {
                totalBonus += def.score
            }
        }
        return totalBonus
    }
}