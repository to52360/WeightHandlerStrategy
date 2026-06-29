package lin.ui.service

import lin.rule.tree.EvaluatorTreeBindingType
import lin.rule.tree.EvaluatorTreeConfig
import lin.ui.tree_config.db.TreeConfigEntity
import lin.ui.tree_config.db.TreeConfigRepository

/**
 * 策略解析运行上下文
 */
data class StrategyEvaluationContext(
    val managerId: String?,                  // 当前卡组 ID
    val bindingType: EvaluatorTreeBindingType,// 寻找的目标绑定类型
    val bindingId: String                    // 寻找的目标 ID (如具体卡牌/卡组/用途标签)
)

/**
 * 评估树策略解析责任链（解耦运行时推导与数据库存储）
 */
class EvaluatorTreeResolver(
    private val treeConfigService: TreeConfigService,
    private val repository: TreeConfigRepository
) {
    /**
     * 按照阶梯优先级解析最佳匹配策略：
     * 阶梯 1: 专属卡组 + 专属绑定 (最高优先级)
     * 阶梯 2: 专属卡组 + 用途兜底 (卡组内部通用用途兜底)
     * 阶梯 3: 全局共享 + 用途兜底 (跨卡组全局完全共享兜底)
     */
    fun resolve(context: StrategyEvaluationContext): Pair<TreeConfigEntity, EvaluatorTreeConfig?>? {
        val matchingSteps = sequenceOf(
            // 阶梯 1: 专属卡组 + 专属绑定
            {
                if (context.managerId != null) {
                    findMatch(context.managerId, context.bindingType, context.bindingId)
                } else null
            },
            // 阶梯 2: 专属卡组 + 用途兜底
            {
                if (context.managerId != null && context.bindingType != EvaluatorTreeBindingType.PURPOSE_TAG) {
                    findMatch(context.managerId, EvaluatorTreeBindingType.PURPOSE_TAG, context.bindingId)
                } else null
            },
            // 阶梯 3: 全局共享 + 用途兜底
            {
                findMatch(null, EvaluatorTreeBindingType.PURPOSE_TAG, context.bindingId)
            }
        )

        // 纯函数式懒加载短路求值：找到第一个非空匹配立刻返回
        return matchingSteps.firstNotNullOfOrNull { step -> step() }
    }

    private fun findMatch(
        managerId: String?,
        bindingType: EvaluatorTreeBindingType,
        bindingId: String
    ): Pair<TreeConfigEntity, EvaluatorTreeConfig?>? {
        val configs = treeConfigService.loadByManagerId(managerId)
        return configs.firstOrNull { (entity, config) ->
            entity.bindingType == bindingType.name &&
                    entity.bindingIds.split(",").map { it.trim() }.contains(bindingId)
        }
    }
}
