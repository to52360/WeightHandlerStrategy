package lin.di

import lin.bean.CardCombinedConfig
import lin.domain.use.plan.UsePlanBuilder
import lin.rule.RuleInfoRegister
import lin.rule.handler.GuardCompiler
import lin.rule.handler.LeafLogicAssembler
import lin.rule.handler.RuleTreeBindingTask
import lin.rule.handler.ScoreCompiler
import lin.serviceLoader.provider.ConditionTreeConfigProvider
import lin.utils.startup.CardConfigBindingTask
import lin.utils.startup.StartupTask
import org.koin.core.qualifier.named
import org.koin.dsl.module

val ruleModule = module {
    single<RuleInfoRegister> {
        val infos = get<Map<String, CardCombinedConfig>>(named("weightInfo")).values.map { it.weightInfo }
        RuleInfoRegister(infos, get())
    }

    single<UsePlanBuilder> { UsePlanBuilder() }

    // 🌟 先注册配置组装，后注册规则树绑定，保证 StartupTask 执行顺序
    single<GuardCompiler> {
        GuardCompiler(get(), getAll<ConditionTreeConfigProvider>(), get())
    }
    single<ScoreCompiler> { ScoreCompiler(get(), get(), get()) }
    single<LeafLogicAssembler> { LeafLogicAssembler(get(), get()) }

    single<StartupTask>(named("cardConfigBinding")) { CardConfigBindingTask() }
    single<StartupTask>(named("ruleTreeBinding")) { RuleTreeBindingTask() }
}
