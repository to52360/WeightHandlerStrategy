package lin.utils.startup

import lin.bean.CardCombinedConfig
import lin.bean.CardWeightInfo
import lin.bean.usePlan.*
import lin.domain.use.plan.CardPurposeProvider
import lin.domain.use.plan.ComboAssembler
import lin.domain.use.plan.GroupUseOverrideProvider
import lin.domain.use.plan.UseIntentAssembler
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
        // 1. SPI 拉取基础权重 Map
        val baseInfos = HashMap<String, CardWeightInfo>()
        ServiceLoaderUtils.loadServices(CardWeightInfoProvide::class.java).forEach { provider ->
            baseInfos.putAll(provider.getInfos())
        }

        // 2. SPI 拉取卡牌分组 Map
        val groupMap = HashMap<String, MutableSet<String>>()
        ServiceLoaderUtils.loadServices(CardGroupIndexProvider::class.java).forEach { provider ->
            provider.provide().forEach { (cardId, groupIds) ->
                groupMap.getOrPut(cardId) { linkedSetOf() }.addAll(groupIds)
            }
        }

        // 3. 启动期一次性读取 + 组装：Assembler 自解析 cardId，此处只接线
        val cardPurposes = loadCardPurposes(baseInfos.keys)
        val useIntentAsm = UseIntentAssembler(
            cardPurposes, groupMap,
            loadGroupUseOverrides(groupMap.values.flatten().toSet())
        )
        val comboAsm = ComboAssembler(groupMap, loadComboDefinitions())

        val finalMap: Map<String, CardCombinedConfig> = baseInfos.mapValues { (cardId, weightInfo) ->
            CardCombinedConfig(
                weightInfo = weightInfo,
                groupIds = groupMap[cardId].orEmpty(),
                useIntent = useIntentAsm.assemble(cardId),
                comboEntries = comboAsm.entries(cardId),
                comboUseBindings = comboAsm.bindings(cardId)
            )
        }

        // 5. Koin 注册
        loadKoinModules(module {
            single<Map<String, CardCombinedConfig>>(named("weightInfo")) { finalMap }
            single { PurposeTagStore(tags = cardPurposes.mapValues { it.value.purposeTags }) }
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

}
