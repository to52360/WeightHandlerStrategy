package lin.di

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import lin.bean.CardCombinedConfig
import lin.domain.use.plan.UsePlanBuilder
import lin.rule.RuleInfoRegister
import lin.rule.condition.ConditionRegistry
import lin.rule.condition.orthogonal.ConditionAssembler
import lin.rule.handler.RuleTreeBindingTask
import lin.rule.registry.RuleRegistry
import lin.rule.score.ScoreOperatorRegistry
import lin.serviceLoader.provider.*
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
    single<ScoreOperatorRegistry> {
        val providers = ServiceLoaderUtils.loadServices(ScoreOperatorProvider::class.java)
        ScoreOperatorRegistry(providers)
    }
    single {
        val dataSources = ServiceLoaderUtils.loadServices(DataSourceProvider::class.java)
            .flatMap { it.get() }
            .associateBy { it.id }

        val operators = ServiceLoaderUtils.loadServices(OperatorProvider::class.java)
            .flatMap { it.get() }
            .associateBy { it.id }

        ConditionAssembler(
            dataSources = dataSources,
            operators = operators,
            objectMapper = jacksonObjectMapper().apply {
                configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            }
        )
    }
    single<ConditionRegistry> {
        val providers = ServiceLoaderUtils.loadServices(ConditionRegistrationProvider::class.java)
        ConditionRegistry(providers, get())
    }
    single<RuleInfoRegister> {
        val infos = get<Map<String, CardCombinedConfig>>(named("weightInfo")).values.map { it.weightInfo }
        RuleInfoRegister(infos, get())
    }

    single<UsePlanBuilder> { UsePlanBuilder() }

    // 🌟 先注册配置组装，后注册规则树绑定，保证 StartupTask 执行顺序
    single<StartupTask>(named("cardConfigBinding")) { CardConfigBindingTask() }
    single<StartupTask>(named("ruleTreeBinding")) { RuleTreeBindingTask() }
}
