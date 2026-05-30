package lin.bean.usePlan

/**
 * 卡牌视角的 combo 绑定。
 *
 * 这是启动期从 ComboPlanDefinition 预解析出来的只读结构，用额外内存换运行期少做
 * definition/group 解析。它只服务选牌阶段的评分和硬互斥，不表达出牌顺序。
 */
data class CardComboBinding(
    val comboId: String,
    val role: ComboRole,
    val ownGroupId: String,
    val counterpartGroupIds: Set<String>,
    val score: Double = 0.0,
    val coreMutex: Boolean = true
)

/**
 * 卡牌视角的出牌编排绑定。
 *
 * 它只由 ComboPlanDefinition.relation 转换而来，服务 UsePlanOrderer。
 * 评分用的 SCORE_ONLY combo 不会进入这里，避免软惩罚配置影响出牌顺序。
 */
data class CardComboUseBinding(
    val comboId: String,
    val beforeGroupIds: Set<String>,
    val afterGroupIds: Set<String>
)

enum class ComboRole {
    CORE,
    DEP
}
