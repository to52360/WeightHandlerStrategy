package lin.di

import lin.domain.use.plan.UsePlanBuilder
import lin.rule.handler.*
import lin.serviceLoader.provider.AuraBoostConfigProvider
import lin.serviceLoader.provider.ConditionTreeConfigProvider
import lin.serviceLoader.provider.StartupTask
import lin.utils.startup.*
import org.koin.core.qualifier.named
import org.koin.dsl.module

val ruleModule = module {
    single<UsePlanBuilder> { UsePlanBuilder(get()) }

    // 🌟 先注册配置组装，后注册规则树绑定，保证 StartupTask 执行顺序
    single<GuardCompiler> {
        GuardCompiler(get(), getAll<ConditionTreeConfigProvider>(), get())
    }
    single<ScoreCompiler> { ScoreCompiler(get(), get(), get()) }
    single<LeafLogicAssembler> { LeafLogicAssembler(get(), get()) }
    single<AuraBoostEvaluator> { AuraBoostEvaluator(get(), getAll<AuraBoostConfigProvider>()) }

    // 配置绑定步骤：每个维度一个 Step，新增维度在 listOf 里加一行。
    //
    // ⚠️ 两条 Koin 陷阱都在这里踩过（2026-09-22 由 `StrategyProviderModuleWiringTest` 实测）：
    // ① 不能写成 7 个 `single<ConfigBindingStep> { … }` —— Koin 按 (primaryType, qualifier) 归档定义，
    //    **同类型 + 无条件符会互相覆盖**，实测只剩最后注册的 `ComboStep`（steps=1）⇒ 权重/分组索引/
    //    组行为/谓词组/用途/清场策略**全部静默不装配**且不报错。
    // ② 换成 `single<List<ConfigBindingStep>>` 仍不够 —— `List<X>` 擦除后 primaryType 就是 `List`，
    //    会被任何其它「未限定符的列表定义」覆盖（实测被 strategyProviderModule 里那个 bindings 列表
    //    覆盖成了 0 个）。
    // ⇒ 必须**列表 + 限定符**：键唯一，无覆盖可能；新增步骤仍只需一行。
    single<List<ConfigBindingStep>>(named(CONFIG_BINDING_STEPS)) {
        listOf(
            WeightInfoStep(),
            GroupIndexStep(),
            GroupBehaviorStep(),
            PredicateGroupStep(),
            PurposeStep(),
            CardPurposeBehaviorStep(),
            ComboStep(),
        )
    }

    single<StartupTask>(named("cardConfigBinding")) { CardConfigBindingTask(get(named(CONFIG_BINDING_STEPS))) }
    single<StartupTask>(named("ruleTreeBinding")) { RuleTreeBindingTask() }
}

/** 配置绑定步骤列表的限定符；`StrategyProviderModuleWiringTest` 据此断言七个步骤一个不少。 */
const val CONFIG_BINDING_STEPS = "configBindingSteps"
