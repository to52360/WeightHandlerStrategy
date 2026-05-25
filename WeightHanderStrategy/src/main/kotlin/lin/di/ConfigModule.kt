package lin.di

import lin.bean.CardWeightInfo
import lin.config.ConfigDispatcher
import lin.config.find.BindingGroupFinder
import lin.config.find.def.WeightInfoFinder
import lin.config.find.findBy
import lin.config.handler.ConfigHandler
import lin.config.handler.RuleConfigHandler
import lin.config.handler.UseConfigHandler
import lin.rule.RuleInfoRegister
import lin.rule.condition.ConditionRegistry
import lin.rule.handler.RuleTreeBindingTask
import lin.rule.registry.RuleRegistry
import lin.rule.tree.BindingGroupId
import lin.serviceLoader.cardInfoProvide.CardWeightInfoProvide
import lin.serviceLoader.provider.ConditionRegistrationProvider
import lin.serviceLoader.provider.RuleRegistrationProvider
import lin.utils.serviceLoader.ServiceLoaderUtils
import lin.utils.startup.StartupTask
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

val configModule = module {
    single<Map<String, CardWeightInfo>>(named("weightInfo")) {
        val infoMap: MutableMap<String, CardWeightInfo> = HashMap()
        ServiceLoaderUtils.loadServices(CardWeightInfoProvide::class.java).forEach {
            infoMap.putAll(it.getInfos())
        }
        infoMap
    }

    singleOf(::UseConfigHandler) bind ConfigHandler::class
    singleOf(::RuleConfigHandler) bind ConfigHandler::class

    single<WeightInfoFinder<String>>(named("finderByTypeString")) {
        val infoMap: Map<String, CardWeightInfo> = get(named("weightInfo"))
        //根据cardId查找
        findBy { cardId -> listOfNotNull(infoMap[cardId]) }
    }
    single<WeightInfoFinder<Double>>(named("finderByTypeDouble")) {
        val infoMap = get<Map<String, CardWeightInfo>>(named("weightInfo")).values.groupBy { it.groupId }
        //根据 groupId 查找
        findBy { groupId -> infoMap[groupId] ?: emptyList() }
    }
    single<WeightInfoFinder<BindingGroupId>> {
        BindingGroupFinder(get(named("finderByTypeString")))
    }

    single<ConfigDispatcher> { ConfigDispatcher(getAll(), getAll()) }
    single<RuleInfoRegister> {
        val infos = get<Map<String, CardWeightInfo>>(named("weightInfo")).values
        RuleInfoRegister(infos, get())
    }
    single<RuleRegistry> {
        val providers = ServiceLoaderUtils.loadServices(RuleRegistrationProvider::class.java)
        RuleRegistry(providers)
    }
    single<ConditionRegistry> {
        val providers = ServiceLoaderUtils.loadServices(ConditionRegistrationProvider::class.java)
        ConditionRegistry(providers)
    }

    single<StartupTask> { RuleTreeBindingTask() }
}
