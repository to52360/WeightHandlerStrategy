package lin.utils.startup

import lin.bean.CardCombinedConfig
import lin.bean.CardWeightInfo
import lin.bean.ConfigSliceScope
import lin.bean.SliceEntry
import lin.bean.usePlan.*
import lin.domain.use.UseStrategy
import lin.domain.use.plan.*
import lin.rule.tree.CardGroupBehavior
import lin.rule.tree.CardGroupBinding
import lin.rule.tree.GroupMembership
import lin.serviceLoader.provider.PurposeTagIntentRuleProvider

/**
 * 卡牌配置绑定步骤：每步只贡献一类原始数据到 [CardCombinedConfigBuilder]，
 * 由 [CardConfigBindingTask] 统一编排后组装为最终 Map。
 *
 * 新增配置维度 = 新增一个 Step 实现并在 RuleModule 注册，不改动 Task 内部逻辑，
 * 也不感知其他 Step 贡献的数据（各 Step 互不可见，仅在 build 时统一组装）。
 */
interface ConfigBindingStep {
    fun contribute(builder: CardCombinedConfigBuilder)
}

/**
 * 配置组装累加器：收集各 Step 贡献的原始数据，[build] 时统一组装为不可变 Map。
 * 组装逻辑（UseIntentAssembler / ComboAssembler）集中在 build，避免散落于各 Step。
 */
class CardCombinedConfigBuilder(
    /**
     * 用途意图规则来源（T-TG-006 SPI 通道）：由 [CardConfigBindingTask] 从 Koin 注入；
     * 默认值供测试 / 无 Koin 场景直接构造。
     */
    private val intentRuleProvider: PurposeTagIntentRuleProvider = DefaultPurposeTagIntentRuleProvider()
) {
    val baseInfos = HashMap<String, CardWeightInfo>()
    val groupMap = HashMap<String, MutableSet<String>>()
    val groupOverrides = HashMap<String, GroupUseOverride>()

    // 分组级空闲放行门槛：groupId → N（SURPLUS_GATE 行为，T-019）
    val groupSurplusGates = HashMap<String, Int>()

    /**
     * 运行时判定的组级配置片段：groupId → 策略列表（T-002）。
     * 仅谓词组走这里——成员在运行时才确定，expandSlices 无法展开成 cardId，
     * 由 ComboCard 构造时用 `hasGroup(groupId)` 判定后合并。
     */
    val groupSlices = HashMap<String, List<UseStrategy>>()

    /** 追加组级策略（运行时判定归属，见 [groupSlices]）。 */
    private fun addStrategiesForGroup(groupId: String, strategies: List<UseStrategy>) {
        groupSlices[groupId] = groupSlices[groupId].orEmpty() + strategies
    }
    // 配置侧声明的使用动作：cardId → 原始策略列表（含 UseBefore/UseAfter，build 时按类型拆分）
    val useStrategiesByCardId = HashMap<String, MutableList<UseStrategy>>()

    /** 给某卡牌追加配置侧声明的使用策略（before/after 混合，build 时按类型拆分到 useStrategies） */
    fun addStrategiesForCard(cardId: String, strategies: List<UseStrategy>) {
        useStrategiesByCardId.getOrPut(cardId) { ArrayList() }.addAll(strategies)
    }

    var cardPurposes: Map<String, CardPurpose> = emptyMap()
    var comboDefinitions: List<ComboPlanDefinition> = emptyList()

    // 来源原始数据
    var groupBehaviors: List<CardGroupBinding> = emptyList()      // group→behaviors（OVERRIDE 用）
    val slices = mutableListOf<SliceEntry>()                       // 任意 scope 的分片（USE_ACTION 等）

    fun build(): Map<String, CardCombinedConfig> {
        val tagIndex = buildTagIndex()
        expandGroupBehaviors()
        expandSlices(tagIndex)
        // T-TG-006：共用**同一个** deriver 实例 —— 静态 useIntent 预推导与运行时组行为重算
        // 若各持一个，规则源可能漂移（一处读库、一处读硬编码）。
        val intentDeriver = UseIntentDeriver(intentRuleProvider)
        val useIntentAsm = UseIntentAssembler(cardPurposes, groupMap, groupOverrides, intentDeriver)
        val comboAsm = ComboAssembler(groupMap, comboDefinitions)
        // T-013：组级行为索引——静态预算与运行时索引**同源且原子**（同一份 groupOverrides /
        // groupSurplusGates / deriver），故运行时对完整组集合重算的结果必是静态预算的超集，
        // 不需要第三份「预算 ∪ 增量」合并逻辑（与 T-012 的 ComboIndex 同构）。
        val behaviorIndex = GroupBehaviorIndex(groupOverrides, groupSurplusGates, intentDeriver)
        GroupBehaviorRuntime.configure(behaviorIndex)
        return baseInfos.mapValues { (cardId, weightInfo) ->
            val strategies = useStrategiesByCardId[cardId].orEmpty()
            val staticGroupIds = groupMap[cardId].orEmpty()
            CardCombinedConfig(
                weightInfo = weightInfo,
                groupIds = staticGroupIds,
                useIntent = useIntentAsm.assemble(cardId),
                comboEntries = comboAsm.entries(cardId),
                comboUseBindings = comboAsm.bindings(cardId),
                useStrategies = strategies,
                groupStrategies = groupSlices,
                purposeTags = cardPurposes[cardId]?.purposeTags ?: emptySet(),
                // 运行时重算 useIntent 的输入快照（cardPurpose.replanAfterUse 的静态值）
                purposeReplanAfterUse = cardPurposes[cardId]?.replanAfterUse ?: false,
                // 以下三项与运行时走**同一解析实现**（GroupBehaviorIndex），仅组集合来源不同
                conditionalStage = behaviorIndex.resolveConditionalStage(staticGroupIds),
                groupSurplusIdleThreshold = behaviorIndex.resolveSurplusGate(staticGroupIds),
            )
        }
    }

    /** 展开分组 OVERRIDE / SURPLUS_GATE 行为。USE_ACTION 走 slices，不在此处理。 */
    private fun expandGroupBehaviors() {
        for (binding in groupBehaviors) {
            for (behavior in binding.behaviors) {
                when (behavior) {
                    is CardGroupBehavior.OverrideBehavior -> groupOverrides[binding.id] = behavior.override
                    is CardGroupBehavior.SurplusGateBehavior -> groupSurplusGates[binding.id] = behavior.idleThreshold
                    is CardGroupBehavior.UseActionBehavior -> {} // 走 slices
                }
            }
        }
    }

    /** 统一解析所有来源的 [SliceEntry]，按 scope 展开到 cardId → [useStrategiesByCardId]。 */
    private fun expandSlices(tagIndex: Map<PurposeTagId, Set<String>>) {
        for ((scope, s) in slices) {
            val cardIds = when (scope) {
                is ConfigSliceScope.Group -> {
                    val binding = groupBehaviors.find { it.id == scope.groupId }
                    when (binding?.membership) {
                        // 静态组：成员清单确定，直接展开到 cardId（原行为）
                        is GroupMembership.Static -> binding.cardIds

                        // 谓词组：成员运行时才判定，此处展开不到任何卡。
                        // 走 groupSlices 保留「组级作用域」，由 ComboCard 构造时用 hasGroup 判定，
                        // 否则 USE_ACTION 对谓词组会**静默失效**（不报错、只是不生效）。
                        is GroupMembership.Predicate, null -> {
                            if (s.useStrategies.isNotEmpty()) {
                                addStrategiesForGroup(scope.groupId, s.useStrategies)
                            }
                            emptyList()
                        }
                    }
                }

                is ConfigSliceScope.Tag -> tagIndex[scope.tagId].orEmpty()
                is ConfigSliceScope.Card -> listOf(scope.cardId)
            }
            if (s.useStrategies.isNotEmpty()) {
                cardIds.forEach { cardId -> addStrategiesForCard(cardId, s.useStrategies) }
            }
        }
    }

    /** 从 [cardPurposes] 预建用途标签→cardIds 反向索引。 */
    private fun buildTagIndex(): Map<PurposeTagId, Set<String>> {
        val index = mutableMapOf<PurposeTagId, MutableSet<String>>()
        cardPurposes.forEach { (cardId, purpose) ->
            purpose.purposeTags.forEach { tag ->
                index.getOrPut(tag) { mutableSetOf() }.add(cardId)
            }
        }
        return index.mapValues { it.value.toSet() }
    }
}
