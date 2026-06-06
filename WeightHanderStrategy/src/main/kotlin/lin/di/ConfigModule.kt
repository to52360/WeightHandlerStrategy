package lin.di

import lin.bean.CardCombinedConfig
import lin.config.ConfigDispatcher
import lin.config.find.BindingGroupFinder
import lin.config.find.PurposeTagFinder
import lin.config.find.def.WeightInfoFinder
import lin.config.find.findBy
import lin.config.handler.ConfigHandler
import lin.config.handler.RuleConfigHandler
import lin.config.handler.UseConfigHandler
import lin.rule.tree.BindingGroupId
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
    single<WeightInfoFinder<BindingGroupId>> {
        BindingGroupFinder(get(named("finderByTypeString")))
    }
    single<WeightInfoFinder<PurposeTagBindingId>> {
        PurposeTagFinder(get(named("finderByTypeString")))
    }

    single<ConfigDispatcher> { ConfigDispatcher(getAll(), getAll()) }
}
