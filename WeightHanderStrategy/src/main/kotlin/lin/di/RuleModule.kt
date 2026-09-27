package lin.di

import lin.domain.use.plan.UsePlanBuilder
import lin.rule.handler.*
import lin.serviceLoader.provider.AuraBoostConfigProvider
import lin.serviceLoader.provider.ConditionTreeConfigProvider
import org.koin.dsl.module

val ruleModule = module {
    single<UsePlanBuilder> { UsePlanBuilder(get()) }

    // 编译器 / 评估器装配（彼此无顺序依赖）
    single<GuardCompiler> {
        GuardCompiler(get(), getAll<ConditionTreeConfigProvider>(), get())
    }
    single<ScoreCompiler> { ScoreCompiler(get(), get(), get()) }
    single<LeafLogicAssembler> { LeafLogicAssembler(get(), get()) }
    single<AuraBoostEvaluator> { AuraBoostEvaluator(get(), getAll<AuraBoostConfigProvider>()) }

    // 配置装配步骤与启动任务的清单一律在 [StartupPlan]（D-FO-009）：
    // 过程对象不进容器（跑完即无用却被 `single` 永久持有），执行顺序从「Koin 定义顺序（隐式）」
    // 改为「代码显式清单」。新增步骤只改 `StartupPlan`；此处再注册 `StartupTask` 会被静默忽略。
}
