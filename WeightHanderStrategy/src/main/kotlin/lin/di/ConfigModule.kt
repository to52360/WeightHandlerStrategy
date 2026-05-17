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
import lin.rule.registry.RuleRegistry
import lin.rule.tree.BindingGroupId
import lin.rule.tree.TreeConfigProvider
import lin.serviceLoader.provider.RuleRegistrationProvider
import lin.utils.serviceLoader.ServiceLoaderUtils
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

val configModule = module {
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

    // 通过 SPI 动态加载配置提供者，并纳入 Koin 管理
    single<List<TreeConfigProvider>> {
        ServiceLoaderUtils.loadServices(TreeConfigProvider::class.java)
    }
}
