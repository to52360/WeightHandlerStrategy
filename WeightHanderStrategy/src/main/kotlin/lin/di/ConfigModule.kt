package lin.di

import lin.bean.CardCombinedConfig
import lin.config.ConfigDispatcher
import lin.config.find.BindingGroupFinder
import lin.config.find.CardBindingFinder
import lin.config.find.PurposeTagFinder
import lin.config.find.def.WeightInfoFinder
import lin.config.find.findBy
import lin.config.handler.ConfigHandler
import lin.config.handler.RuleConfigHandler
import lin.config.handler.UseConfigHandler
import lin.rule.tree.BindingGroupId
import lin.rule.tree.CardBindingId
import lin.rule.tree.PurposeTagBindingId
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

val configModule = module {
    // ❌ 删去了 weightInfo 单例的声明，完全交给 StartupTask 装配和动态注册

    singleOf(::UseConfigHandler) bind ConfigHandler::class
    singleOf(::RuleConfigHandler) bind ConfigHandler::class

    single<WeightInfoFinder<String>>(named("finderByTypeString")) {
        val infoMap: Map<String, CardCombinedConfig> = get(named("weightInfo"))
        //根据cardId查找
        findBy { cardId -> listOfNotNull(infoMap[cardId]?.weightInfo) }
    }
    single<WeightInfoFinder<Double>>(named("finderByTypeDouble")) {
        val infoMap = get<Map<String, CardCombinedConfig>>(named("weightInfo")).values.groupBy { it.weightInfo.groupId }
        //根据 groupId 查找
        findBy { groupId -> (infoMap[groupId] ?: emptyList()).map { it.weightInfo } }
    }
    // ⚠️ 三个「路由键 finder」必须**各带限定符**并**显式成表**，不能靠 getAll()：
    // 它们的 Koin 定义类型都是 `WeightInfoFinder<*>`（泛型被擦除）⇒ 无名注册会共用同一个 key
    // 互相覆盖，只剩最后注册的那个。2026-09-22 宿主实测：`getAll<WeightInfoFinder<*>>()` 只收到
    // CardBindingId 一个 ⇒ `RuleTreeBindingTask` 派发 GROUP / PURPOSE_TAG 树根时查不到 finder，
    // 日志只留一行 `WARN 不支持类型`，**这两类绑定的评估树整体静默失效**（树分恒为 0）。
    single<WeightInfoFinder<BindingGroupId>>(named(FINDER_BY_BINDING_GROUP)) {
        BindingGroupFinder(get(named("finderByTypeString")))
    }
    single<WeightInfoFinder<PurposeTagBindingId>>(named(FINDER_BY_PURPOSE_TAG)) {
        PurposeTagFinder(get(named("finderByTypeString")))
    }
    single<WeightInfoFinder<CardBindingId>>(named(FINDER_BY_CARD)) {
        CardBindingFinder(get(named("finderByTypeString")))
    }

    single<ConfigDispatcher> {
        // handlers 走 getAll 没问题（那两个经 `bind ConfigHandler::class` 注册，各有独立 key）；
        // bindInfoFind 必须显式列出——理由见上。
        val finders: List<WeightInfoFinder<*>> = listOf(
            get(named("finderByTypeString")),
            get(named("finderByTypeDouble")),
            get(named(FINDER_BY_BINDING_GROUP)),
            get(named(FINDER_BY_PURPOSE_TAG)),
            get(named(FINDER_BY_CARD)),
        )
        ConfigDispatcher(getAll(), finders)
    }
}

/** 路由键 finder 的限定符；由 `KoinFinderWiringTest` 断言「一个都不能少」。 */
const val FINDER_BY_BINDING_GROUP = "finderByBindingGroup"
const val FINDER_BY_PURPOSE_TAG = "finderByPurposeTag"
const val FINDER_BY_CARD = "finderByCard"
