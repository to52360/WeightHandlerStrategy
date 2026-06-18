package lin.di

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import lin.bean.CardCombinedConfig
import lin.domain.use.plan.UsePlanBuilder
import lin.rule.RuleInfoRegister
import lin.rule.condition.ConditionRegistry
import lin.rule.condition.PipelineAssembler
import lin.rule.handler.RuleTreeBindingTask
import lin.rule.registry.RuleRegistry
import lin.rule.score.ScoreOperatorRegistry
import lin.serviceLoader.provider.DataSourceProvider
import lin.serviceLoader.provider.OperatorProvider
import lin.serviceLoader.provider.TransformProvider
import lin.utils.serviceLoader.loadSpiList
import lin.utils.startup.CardConfigBindingTask
import lin.utils.startup.StartupTask
import org.koin.core.qualifier.named
import org.koin.dsl.module

val ruleModule = module {
    single<RuleRegistry> {
        RuleRegistry(loadSpiList())
    }
    single<ScoreOperatorRegistry> {
        ScoreOperatorRegistry(loadSpiList())
    }
    single {
        val dataSources = loadSpiList<DataSourceProvider>()
            .flatMap { it.get() }
            .associateBy { it.id }

        val transforms = loadSpiList<TransformProvider>()
            .flatMap { it.get() }
            .associateBy { it.id }

        val operators = loadSpiList<OperatorProvider>()
            .flatMap { it.get() }
            .associateBy { it.id }

        PipelineAssembler(
            dataSources = dataSources,
            transforms = transforms,
            operators = operators,
            objectMapper = jacksonObjectMapper().apply {
                configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            }
        )
    }
    single<ConditionRegistry> {
        ConditionRegistry(loadSpiList(), get())
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
