package lin.utils.startup

import lin.bean.CardCombinedConfig
import lin.bean.CardWeightInfo
import lin.bean.usePlan.*
import lin.domain.use.plan.UseIntentProvider
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
        val useIntentByGroupId = loadUseIntentByGroupId(groupMap.values.flatten().toSet())
        val comboDefinitions = loadComboDefinitions()
        val comboBindingByGroupId = comboDefinitions.toComboBindingByGroupId()
        val comboUseBindingByGroupId = comboDefinitions.toComboUseBindingByGroupId()

        // 4. 统一赋值组装只读 finalMap (扁平化，直接传入 groupIds)
        val finalMap: Map<String, CardCombinedConfig> = baseInfos.mapValues { (cardId, weightInfo) ->
            val groupsSet = groupMap[cardId] ?: emptySet()
            val useIntent = groupsSet.firstNotNullOfOrNull { useIntentByGroupId[it] }
            val comboBindings = groupsSet
                .flatMap { comboBindingByGroupId[it] ?: emptyList() }
                .distinctBy { "${it.comboId}:${it.role}:${it.ownGroupId}" }
            val comboUseBindings = groupsSet
                .flatMap { comboUseBindingByGroupId[it] ?: emptyList() }
                .distinctBy { "${it.comboId}:${it.beforeGroupIds}:${it.afterGroupIds}" }

            CardCombinedConfig(
                weightInfo = weightInfo,
                groupIds = groupsSet,
                useConfig = CardUseConfig(),
                useIntent = useIntent,
                comboBindings = comboBindings,
                comboUseBindings = comboUseBindings
            )
        }

        // 5. 🌟 动态向 Koin 注册不可变的完全体只读 Map！
        loadKoinModules(module {
            single<Map<String, CardCombinedConfig>>(named("weightInfo")) { finalMap }
        })
    }

    private fun loadUseIntentByGroupId(groupIds: Set<String>): Map<String, UseIntent> {
        if (groupIds.isEmpty()) return emptyMap()

        val provider = runCatchingLog("加载 UseIntentProvider 失败，使用空使用配置") {
            get<UseIntentProvider>()
        }.getOrNull() ?: return emptyMap()

        return groupIds.associateWith { groupId -> provider.intentOf(setOf(groupId)) }
    }

    private fun loadComboDefinitions(): List<ComboPlanDefinition> {
        val provider = runCatchingLog("加载 ComboPlanDefinitionProvider 失败，使用空 combo 编排定义") {
            get<ComboPlanDefinitionProvider>()
        }.getOrNull() ?: return emptyList()

        return provider.findAll()
    }

    private fun List<ComboPlanDefinition>.toComboBindingByGroupId(): Map<String, List<CardComboBinding>> {
        val index = linkedMapOf<String, MutableList<CardComboBinding>>()
        forEach { definition ->
            definition.coreGroupIds.forEach { groupId ->
                index.getOrPut(groupId) { mutableListOf() }.add(
                    CardComboBinding(
                        comboId = definition.id,
                        role = ComboRole.CORE,
                        ownGroupId = groupId,
                        counterpartGroupIds = definition.depGroupIds,
                        score = definition.score,
                        coreMutex = definition.coreMutex
                    )
                )
            }
            definition.depGroupIds.forEach { groupId ->
                index.getOrPut(groupId) { mutableListOf() }.add(
                    CardComboBinding(
                        comboId = definition.id,
                        role = ComboRole.DEP,
                        ownGroupId = groupId,
                        counterpartGroupIds = definition.coreGroupIds,
                        score = definition.score,
                        coreMutex = definition.coreMutex
                    )
                )
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
