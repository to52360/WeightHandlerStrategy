package lin.utils.startup

import lin.bean.CardCombinedConfig
import lin.bean.CardWeightInfo
import lin.bean.usePlan.*
import lin.domain.use.plan.CardPurposeProvider
import lin.domain.use.plan.GroupUseOverrideProvider
import lin.serviceLoader.cardInfoProvide.CardWeightInfoProvide
import lin.serviceLoader.provider.CardGroupIndexProvider
import lin.utils.runCatchingLog
import lin.utils.serviceLoader.ServiceLoaderUtils
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.context.loadKoinModules
import org.koin.core.qualifier.named
import org.koin.dsl.module

class CardConfigBindingTask : StartupTask, KoinComponent {

    override fun execute() {
        // 1. 统一提取：拉取基础权重 Map
        val baseInfos = HashMap<String, CardWeightInfo>()
        ServiceLoaderUtils.loadServices(CardWeightInfoProvide::class.java).forEach { provider ->
            baseInfos.putAll(provider.getInfos())
        }

        // 2. 统一提取：拉取卡牌分组 Map
        val groupMap = HashMap<String, MutableSet<String>>()
        ServiceLoaderUtils.loadServices(CardGroupIndexProvider::class.java).forEach { provider ->
            provider.provide().forEach { (cardId, groupIds) ->
                groupMap.getOrPut(cardId) { linkedSetOf() }.addAll(groupIds)
            }
        }

        // 3. 启动期一次性读取使用配置和 combo 编排定义，避免运行时每次出牌再查询 provider。
        val allCardIds = baseInfos.keys
        val allGroupIds = groupMap.values.flatten().toSet()
        val cardPurposes = loadCardPurposes(allCardIds)
        val groupOverrides = loadGroupUseOverrides(allGroupIds)
        val comboDefinitions = loadComboDefinitions()
        val comboDefByGroupId = comboDefinitions.indexComboDefByGroupId()
        val comboUseBindingByGroupId = comboDefinitions.toComboUseBindingByGroupId()

        // 4. 统一赋值组装只读 finalMap (扁平化，直接传入 groupIds)
        val finalMap: Map<String, CardCombinedConfig> = baseInfos.mapValues { (cardId, weightInfo) ->
            val groupsSet = groupMap[cardId] ?: emptySet()
            // 合并 CardPurpose + GroupUseOverride → CardUseConfig（优先级：GroupUseOverride > CardPurpose > 默认值）
            val cardPurpose = cardPurposes[cardId] ?: CardPurpose()
            val groupOverride = groupsSet.firstNotNullOfOrNull { groupOverrides[it] }
            val useConfig = CardUseConfig(
                purposeTags = cardPurpose.purposeTags,
                stageOverride = groupOverride?.stageOverride,              // null = 不覆盖，走标签推导
                replanAfterUse = groupOverride?.replanAfterUse
                    ?: cardPurpose.replanAfterUse,                         // 分组覆盖优先
                orderWeight = groupOverride?.orderWeight ?: 0.0
            )
            // 直接从定义归并生成 CardComboEntry，不再经过 CardComboBinding 中间层
            val comboEntries = groupsSet
                .flatMap { comboDefByGroupId[it] ?: emptyList() }
                .distinctBy { it.id }
                .map { def ->
                    CardComboEntry(
                        comboId = def.id,
                        score = def.score,
                        coreMutexOwnGroupIds = if (def.coreMutex)
                            def.coreGroupIds.filter { it in groupsSet }
                        else emptyList(),
                        counterpartGroupIds = def.depGroupIds + def.coreGroupIds
                    )
                }
            val comboUseBindings = groupsSet
                .flatMap { comboUseBindingByGroupId[it] ?: emptyList() }
                .distinctBy { "${it.comboId}:${it.beforeGroupIds}:${it.afterGroupIds}" }

            CardCombinedConfig(
                weightInfo = weightInfo,
                groupIds = groupsSet,
                useConfig = useConfig,
                comboEntries = comboEntries,
                comboUseBindings = comboUseBindings
            )
        }

        // 5. 🌟 动态向 Koin 注册不可变的完全体只读 Map！
        loadKoinModules(module {
            single<Map<String, CardCombinedConfig>>(named("weightInfo")) { finalMap }
        })
    }

    private fun loadCardPurposes(cardIds: Set<String>): Map<String, CardPurpose> {
        if (cardIds.isEmpty()) return emptyMap()
        val provider = runCatchingLog("加载 CardPurposeProvider 失败，使用空用途配置") {
            get<CardPurposeProvider>()
        }.getOrNull() ?: return emptyMap()
        return provider.purposeOf(cardIds)
    }

    private fun loadGroupUseOverrides(groupIds: Set<String>): Map<String, GroupUseOverride> {
        if (groupIds.isEmpty()) return emptyMap()
        val provider = runCatchingLog("加载 GroupUseOverrideProvider 失败，使用空分组覆盖") {
            get<GroupUseOverrideProvider>()
        }.getOrNull() ?: return emptyMap()
        return provider.overridesOf(groupIds)
    }

    private fun loadComboDefinitions(): List<ComboPlanDefinition> {
        val provider = runCatchingLog("加载 ComboPlanDefinitionProvider 失败，使用空 combo 编排定义") {
            get<ComboPlanDefinitionProvider>()
        }.getOrNull() ?: return emptyList()

        return provider.findAll()
    }

    /**
     * 按 groupId 直接索引 ComboPlanDefinition，省去中间 CardComboBinding 层。
     * 一张定义归入其所有 core/dep group，供后续 per-card 归并。
     */
    private fun List<ComboPlanDefinition>.indexComboDefByGroupId(): Map<String, List<ComboPlanDefinition>> {
        val index = linkedMapOf<String, MutableList<ComboPlanDefinition>>()
        forEach { definition ->
            (definition.coreGroupIds + definition.depGroupIds).forEach { groupId ->
                index.getOrPut(groupId) { mutableListOf() }.add(definition)
            }
        }
        return index
    }

    private fun List<ComboPlanDefinition>.toComboUseBindingByGroupId(): Map<String, List<CardComboUseBinding>> {
        val index = linkedMapOf<String, MutableList<CardComboUseBinding>>()
        forEach { definition ->
            val binding = definition.toUseBinding() ?: return@forEach
            (binding.beforeGroupIds + binding.afterGroupIds).forEach { groupId ->
                index.getOrPut(groupId) { mutableListOf() }.add(binding)
            }
        }
        return index
    }

    /**
     * 把 combo 定义里的编排关系预解析成运行期可直接使用的组级顺序绑定。
     * SCORE_ONLY 只服务评分，不进入出牌编排。
     */
    private fun ComboPlanDefinition.toUseBinding(): CardComboUseBinding? {
        return when (relation) {
            ComboRelation.SCORE_ONLY -> null
            ComboRelation.CORE_BEFORE_DEP -> CardComboUseBinding(id, coreGroupIds, depGroupIds)
            ComboRelation.DEP_BEFORE_CORE -> CardComboUseBinding(id, depGroupIds, coreGroupIds)
        }
    }
}
