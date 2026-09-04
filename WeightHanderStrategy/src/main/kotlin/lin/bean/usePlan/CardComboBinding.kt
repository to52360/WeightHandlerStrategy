package lin.bean.usePlan

/**
 * 启动期按 comboId 归并后的只读条目。
 *
 * 一张卡对同一个 combo 可能涉及多个 group（CORE / DEP），在启动组装阶段
 * 直接由 ComboPlanDefinition 归并成一条。运行时 findBestCombination 不再需要
 * scoredComboIds 去重和 coreMutex 二次过滤。
 */
data class CardComboEntry(
    val comboId: String,
    val score: Double,
    /** 不为空表示该卡在此 combo 中有 coreMutex 限制，需做互斥检查 */
    val coreMutexOwnGroupIds: List<String> = emptyList(),
    /** counterpart 组集合（CORE 与 DEP 的 counterpart 取并集） */
    val counterpartGroupIds: Set<String> = emptySet(),
    /**
     * 本 combo 的**核心组**集合——判定「这张卡属于核心侧还是依赖侧」的唯一依据
     * （起手换牌 M2「核心与依赖各留一张」需要分侧；`coreMutexOwnGroupIds` 在 `coreMutex=false` 时恒空，不可用于分侧）。
     *
     * 引擎内部 DTO 字段：不落库、不进配置面，仅由 `ComboIndex` 从定义透传。
     */
    val coreGroupIds: Set<String> = emptySet(),
    /**
     * 起手换牌专用组合协同加分（透传 `ComboPlanDefinition.changeScore`）。
     * 只被 `ChangeCardSelector` 消费，**出牌评分不读它**（出牌侧用 [score]）。
     * 0.0 = 不加成；同一 combo 在一次子集评分中只计一次。
     */
    val changeScore: Double = 0.0
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
