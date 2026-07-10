package lin.utils.startup

import lin.bean.CardCombinedConfig
import lin.bean.CardWeightInfo
import lin.bean.usePlan.CardPurpose
import lin.bean.usePlan.ComboPlanDefinition
import lin.bean.usePlan.GroupUseOverride
import lin.domain.use.plan.ComboAssembler
import lin.domain.use.plan.UseIntentAssembler

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
class CardCombinedConfigBuilder {
    val baseInfos = HashMap<String, CardWeightInfo>()
    val groupMap = HashMap<String, MutableSet<String>>()
    val groupOverrides = HashMap<String, GroupUseOverride>()
    var cardPurposes: Map<String, CardPurpose> = emptyMap()
    var comboDefinitions: List<ComboPlanDefinition> = emptyList()

    fun build(): Map<String, CardCombinedConfig> {
        val useIntentAsm = UseIntentAssembler(cardPurposes, groupMap, groupOverrides)
        val comboAsm = ComboAssembler(groupMap, comboDefinitions)
        return baseInfos.mapValues { (cardId, weightInfo) ->
            CardCombinedConfig(
                weightInfo = weightInfo,
                groupIds = groupMap[cardId].orEmpty(),
                useIntent = useIntentAsm.assemble(cardId),
                comboEntries = comboAsm.entries(cardId),
                comboUseBindings = comboAsm.bindings(cardId)
            )
        }
    }
}
