package lin.di

import lin.bean.CardCombinedConfig
import lin.rule.RuleInfoRegister
import lin.rule.condition.ConditionRegistry
import lin.rule.handler.RuleTreeBindingTask
import lin.rule.registry.RuleRegistry
import lin.serviceLoader.provider.ConditionRegistrationProvider
import lin.serviceLoader.provider.RuleRegistrationProvider
import lin.utils.serviceLoader.ServiceLoaderUtils
import lin.utils.startup.CardConfigBindingTask
import lin.utils.startup.StartupTask
import org.koin.core.qualifier.named
import org.koin.dsl.module

val ruleModule = module {
    single<RuleRegistry> {
        val providers = ServiceLoaderUtils.loadServices(RuleRegistrationProvider::class.java)
        RuleRegistry(providers)
    }
    single<ConditionRegistry> {
        val providers = ServiceLoaderUtils.loadServices(ConditionRegistrationProvider::class.java)
        ConditionRegistry(providers)
    }
    single<RuleInfoRegister> {
        val infos = get<Map<String, CardCombinedConfig>>(named("weightInfo")).values.map { it.weightInfo }
        RuleInfoRegister(infos, get())
    }

    // 🌟 先注册配置组装，后注册规则树绑定，保证 StartupTask 执行顺序
    single<StartupTask>(named("cardConfigBinding")) { CardConfigBindingTask() }
    single<StartupTask>(named("ruleTreeBinding")) { RuleTreeBindingTask() }
}
