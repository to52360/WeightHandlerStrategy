package lin.serviceLoader.provider

import lin.bean.AuraBoostConfig
import lin.bean.usePlan.CardPurpose
import lin.bean.usePlan.ComboPlanDefinition
import lin.bean.usePlan.GroupUseOverride
import lin.bean.usePlan.PurposeTagIntentRule
import lin.rule.condition.ConditionTreeConfig
import lin.rule.tree.EvaluatorTreeConfig

/**
 * 卡牌用途标签提供者。
 *
 * SPI 入口，configUi 通过此接口读取 card_purpose 表。
 * Provider 自主决定数据范围（全量/按启用状态筛选/其他数据源）。
 * 引擎端提供默认实现返回空 Map。
 * key 是cardId
 */
fun interface CardPurposeProvider {
    fun findAllEnabled(): Map<String, CardPurpose>

    // ARCH-TODO: 未来按 manager_id 管理或采用 UI 分页，当前暂用 id 集合查询
    fun findByIds(ids: Set<String>): Map<String, CardPurpose> {
        if (ids.isEmpty()) return emptyMap()
        return findAllEnabled().filterKeys { it in ids }
    }
}

/**
 * 用途标签意图规则提供者。
 *
 * SPI 入口，configUi 通过此接口读取 `purpose_tag_rule` 表（T-TG-007）。
 * 引擎端**必须**保留硬编码 fallback（`DefaultPurposeTagIntentRuleProvider`）：
 * 纯引擎运行（无 configUi）或表为空时回落内置 6 条，禁止静默退化为「全无规则」
 * （那会让所有标签失去 stage/N/replan 默认值）。
 *
 * 注意：规则的**有无**由「是否存在该 tagId 的规则」表达 —— 无规则的标签（如 FINISH）
 * 不参与 `UseIntentDeriver` 的 priority 选优，故不可用「全默认值规则」冒充（D-TG-003）。
 */
fun interface PurposeTagIntentRuleProvider {
    fun rules(): List<PurposeTagIntentRule>
}

// 当前数据量下 findById 逐个查询（~30 次）≈ 1.5ms。
// 条件树是无启用概念 of 通用模板，数据量可能较大，不适合全量预加载。
// 后续若数据量增大，Provider 内部加 ConcurrentHashMap 缓存即可防御，consumer 代码无需修改。
interface ConditionTreeConfigProvider {
    fun findById(id: String): ConditionTreeConfig?
    fun findAll(): List<ConditionTreeConfig>
}

/**
 * 分组使用覆盖提供者。
 *
 * @deprecated 行为属性已合并到 CardGroupBinding，由 GroupBehaviorProvider 提供。
 * 保留此接口仅为 SPI 兼容，新代码不应使用。
 */
@Deprecated("行为属性已合并到 CardGroupBinding，使用 GroupBehaviorProvider 替代")
fun interface GroupUseOverrideProvider {
    fun findAllEnabled(): Map<String, GroupUseOverride>
}

interface TreeConfigProvider {
    fun findById(id: String): EvaluatorTreeConfig?
    fun findAll(): List<EvaluatorTreeConfig>
}

fun interface ComboPlanDefinitionProvider {
    /**
     * 加载新 combo 定义。
     * 定义只描述"组关系、加权、互斥和使用关系"，不负责真实出牌。
     */
    fun findAll(): List<ComboPlanDefinition>
}

/**
 * Push 广播评分配置提供者（aura-boost）。
 *
 * SPI 入口，configUi 通过此接口读取 aura_boost 表。
 * 引擎端无默认实现时 get 空列表（boosts 为空，AuraBoostEvaluator.activeScore 返回 0）。
 */
fun interface AuraBoostConfigProvider {
    fun findAll(): List<AuraBoostConfig>
}
