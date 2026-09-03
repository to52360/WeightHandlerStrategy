package lin.domain.use.plan

import lin.bean.ComboCard
import lin.myLog
import lin.rule.condition.ConditionLogic
import lin.rule.context.CardOnlyRuleEnv
import lin.rule.context.RuleContext
import lin.rule.handler.GuardCompiler
import lin.rule.tree.GroupMembership
import java.util.concurrent.ConcurrentHashMap

/**
 * 一个谓词组（条件定义成员的分组）的运行时定义。
 *
 * @param groupId 分组 id（combo 引用的就是这个 id）
 * @param conditionId 条件树 id（condition_tree_config）
 * @param includeDerived **已解析覆盖链后**的最终值：是否纳入卡池外的卡（衍生/发现/随机生成）。
 *   覆盖链 `组级 Predicate.includeDerived > 卡组级 defaultIncludeDerived > 内建兜底 false`
 *   在装配期解析完毕，运行期不再关心层级。
 */
data class PredicateGroupDef(
    val groupId: String,
    val conditionId: String,
    val includeDerived: Boolean
)

/**
 * 谓词组（[GroupMembership.Predicate]）的运行时求值器。
 *
 * ## 为什么是运行时
 * 条件求值的对象是「这张卡自己」——卡牌类型/种族/特征/费用都是 `Card` 上的实时字段，
 * 直接读即可，**不需要卡池、不需要 RuleEnv**（故用 [CardOnlyRuleEnv]）。
 * 启动期展开成 cardIds 反而做不到：那时既没有 ComboCard 也没有战场（详见 Q-001 专项）。
 *
 * ## 缓存
 * **per-ComboCard 实例缓存**（[ComboCard.predicateGroupIds]），本类只做条件逻辑编译缓存。
 *
 * ⚠️ 为什么**不按 cardId 缓存**：卡的运行时属性会变（减费光环改 `cost`、沉默改特征位、
 * buff 改身材）。按 cardId 缓存会让「被减费的卡」拿到减费前的判定结果——
 * 条件若涉及费用就静默错判。ComboCard 每轮重建、属性快照固定，故按实例缓存是正确的粒度，
 * 且天然做到「每轮每卡只求值一次」。
 *
 * ## 求值失败
 * 条件树 id 找不到 / 求值抛异常 → 该组**不计入**，并 warn 一次。
 * 成员判定失败不应让整张卡不可用（组合搜索、起手换牌都要靠它），降级为「不属于该组」最安全。
 */
object GroupMembershipRuntime {

    /**
     * 装配状态快照：defs 与 logicResolver 属同一关注点（一次 configure 的原子产物），
     * 合并为单个不可变对象、单引用切换——消除「两个 @Volatile 字段先后赋值」的竞态窗口
     * （瞬时出现「新 defs + 旧 resolver」组合）。
     */
    private data class RuntimeState(
        val defs: List<PredicateGroupDef> = emptyList(),
        val logicResolver: (String) -> ConditionLogic? = { null }
    )

    @Volatile
    private var state: RuntimeState = RuntimeState()

    /** 条件树 id → 编译好的逻辑。条件树是不可变配置，编译一次长期复用。 */
    private val logicCache = ConcurrentHashMap<String, ConditionLogic>()

    /** 已告警过的失败条件，避免每卡每轮刷日志。 */
    private val warned = ConcurrentHashMap.newKeySet<String>()

    /**
     * 装配期调用：注入谓词组定义与条件逻辑解析器。
     * 重复调用会重置（重新装载配置时）。
     *
     * **defs 为空 = 卡组没有谓词组**：[resolve] 内部第一行即短路返回空集，
     * [lin.bean.ComboCard] 的重算守卫随之恒走静态预算——**无需任何「模式」切换**。
     * 本类只管数据（谓词组定义 + 条件求值），不做分发。
     *
     * @param resolveLogic 条件树 id → 判定逻辑；返回 null 表示找不到/编译失败。
     *   生产侧传 `{ id -> guardCompiler.compileTree(id) }`。
     */
    fun configure(
        defs: List<PredicateGroupDef>,
        resolveLogic: (String) -> ConditionLogic? = { null }
    ) {
        state = RuntimeState(defs, resolveLogic)
        logicCache.clear()
        warned.clear()
    }

    /** 由 [GuardCompiler] 装配（生产路径，PredicateGroupStep 调用）。 */
    fun configure(defs: List<PredicateGroupDef>, compiler: GuardCompiler?) {
        configure(defs) { id ->
            if (compiler == null) null else compileOrNull(id, compiler)
        }
    }

    private fun compileOrNull(id: String, compiler: GuardCompiler): ConditionLogic? =
        try {
            compiler.compileTree(id)
        } catch (e: Throwable) {
            warnOnce(id, e)
            null
        }

    /** 测试用：恢复到未装配状态。 */
    fun clear() = configure(emptyList())

    /** 是否装配了谓词组（供消费端快速短路，避免无谓的 Set 合并分配）。 */
    fun isEmpty(): Boolean = state.defs.isEmpty()

    /**
     * 求这张卡属于哪些谓词组。
     *
     * @param isDerived 该卡是否在卡池外（衍生/发现/随机生成）。
     *   判定依据 `combinedConfig == null`——`MyWarManage.parseComboCard` 用
     *   `infoMap[card.cardId]` 查配置，而 infoMap 按卡池构建。
     */
    fun resolve(card: ComboCard, isDerived: Boolean): Set<String> {
        val current = state
        if (current.defs.isEmpty()) return emptySet()

        var result: MutableSet<String>? = null
        for (def in current.defs) {
            // 卡池外的卡：只有显式 includeDerived 的组才纳入
            if (isDerived && !def.includeDerived) continue

            if (evaluate(def, card)) {
                if (result == null) result = LinkedHashSet()
                result.add(def.groupId)
            }
        }
        return result ?: emptySet()
    }

    private fun evaluate(def: PredicateGroupDef, card: ComboCard): Boolean {
        val logic = logicFor(def.conditionId) ?: return false
        return try {
            logic(RuleContext(card), CardOnlyRuleEnv)
        } catch (e: Throwable) {
            warnOnce(def, e)
            false
        }
    }

    /**
     * 取（并缓存）条件树的编译结果。
     *
     * `crossCard` 保持 `null`（不强制覆盖树内配置）——成员判定是 **per-card 语义**，
     * 若开跨卡缓存（crossCard=true）会让不同卡拿到同一结果
     * （详见 AuraBoostEvaluator 中关于 crossCard 与 per-card 求值的警告）。
     *
     * ⚠️ 不能用 `ConcurrentHashMap.getOrPut`——它不支持 null 值，
     * 解析失败返回 null 时会抛 NPE。故手动查 + 条件写入。
     * 缺失结果**不写入缓存**：条件树可能后续才配置好，负缓存会让配置长期不生效。
     */
    private fun logicFor(conditionId: String): ConditionLogic? {
        logicCache[conditionId]?.let { return it }
        val resolved = state.logicResolver(conditionId)
        if (resolved != null) logicCache[conditionId] = resolved
        return resolved
    }

    private fun warnOnce(def: PredicateGroupDef, e: Throwable) =
        warnOnce("${def.groupId}/${def.conditionId}", e)

    private fun warnOnce(key: String, e: Throwable) {
        if (warned.add(key)) {
            myLog.error(e) { "谓词组条件求值失败，该组按「不匹配」处理：key=$key" }
        }
    }
}
