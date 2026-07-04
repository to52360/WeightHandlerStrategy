package lin.di

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import lin.rule.condition.ConditionRegistry
import lin.rule.condition.PipelineAssembler
import lin.rule.registry.RuleRegistry
import lin.rule.score.ScoreOperatorRegistry
import lin.serviceLoader.provider.DataSourceProvider
import lin.serviceLoader.provider.OperatorProvider
import lin.serviceLoader.provider.TransformProvider
import lin.utils.serviceLoader.loadSpiList
import org.koin.dsl.module

/**
 * 规则引擎共享基础设施模块。
 * PipelineAssembler、RuleRegistry、ConditionRegistry、ScoreOperatorRegistry
 * 在引擎侧和 configUi 侧都会被使用，提取到此模块避免重复定义。
 */
val infraModule = module {
    single<RuleRegistry> { RuleRegistry(loadSpiList()) }
    single<ScoreOperatorRegistry> { ScoreOperatorRegistry(loadSpiList()) }
    single<ConditionRegistry> { ConditionRegistry(loadSpiList(), get()) }
    single<PipelineAssembler> {
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
}
