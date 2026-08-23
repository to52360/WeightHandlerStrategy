package lin.di

import club.xiaojiawei.hsscriptcardsdk.status.WAR
import lin.domain.*
import lin.domain.combo.*
import lin.domain.combo.ComboParse.Companion.BEFORE
import lin.domain.combo.ComboParse.Companion.CHANGE
import lin.domain.combo.ComboParse.Companion.DEF
import lin.domain.combo.ComboParse.Companion.FIRST
import lin.domain.combo.ComboParse.Companion.LAST
import lin.domain.strategy.DefFindStrategy
import lin.domain.strategy.ExtCostStrategy
import lin.domain.strategy.FindComboStrategy
import lin.domain.strategy.FindPlanner
import lin.domain.use.UseDomain
import lin.serviceLoader.weightRule.utils.war.CleanWarUtils
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

private val mainModule = module {
    single { WAR }
    singleOf(::MyWarManage) bind WarInfo::class
    singleOf(::WeightHandlerDomain)
}

private val comBoInfoModule = module {
    singleOf(::LastUseCombo) { named(LAST) } bind ComboParse::class
    singleOf(::ComboImpl) { named(DEF) } bind ComboParse::class
    singleOf(::ComboImpl) { named(BEFORE) } bind ComboParse::class
    singleOf(::ChangeComboParse) { named(CHANGE) } bind ComboParse::class
    singleOf(::FirstUseCombo) { named(FIRST) } bind ComboParse::class
}

private val findStrategyModule = module {
    singleOf(::ExtCostStrategy) bind FindComboStrategy::class
    //singleOf(::ForgeFindStrategy) bind FindComboStrategy::class
    singleOf(::DefFindStrategy) bind FindComboStrategy::class
}

private val utilsModule = module {
    singleOf(::UseDomain)
    singleOf(::FindPlanner)
    singleOf(::CleanWarUtils)
}

private val executionModule = module {
    //每次解析得到独立实例：每个 ComboDomain 持有自己的类加载器上下文与递归栈控制器
    factory { ClassLoaderScope() }
    factory { ComboCycleController() }
}

val domainModule = module {
    includes(mainModule, comBoInfoModule, findStrategyModule, utilsModule, executionModule)
}
