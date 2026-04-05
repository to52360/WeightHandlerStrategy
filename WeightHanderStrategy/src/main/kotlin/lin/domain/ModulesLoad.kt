package lin.domain

import club.xiaojiawei.hsscriptcardsdk.status.WAR
import lin.bean.CardWeightInfo
import lin.config.ConfigDispatcher
import lin.config.find.def.WeightInfoFinder
import lin.config.find.findBy
import lin.config.handler.ConfigHandler
import lin.config.handler.UseConfigHandler
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
import lin.lifecycle.LifecycleRegister
import lin.lifecycle.LifecycleRegisterImpl
import lin.rule.RuleInfoRegister
import lin.rule.build.RuleRegistry
import lin.serviceLoader.findCombo.SkillFindStrategy
import lin.serviceLoader.module.ModulesInfo
import lin.serviceLoader.parse.LieRenParse
import lin.serviceLoader.parse.ParseCardWeightInfo
import lin.serviceLoader.parse.ParseCombo
import lin.serviceLoader.weightRule.utils.war.CleanWarUtils
import lin.utils.database.DefDBUrl
import lin.utils.database.SqliteJdbcProvider
import lin.utils.database.dao.CardInfoDao
import lin.utils.serviceLoader.ServiceLoaderUtils
import lin.weightHandler.condition.config.ComboInfoDao
import lin.weightHandler.condition.config.GroupStrategyDao
import lin.weightHandler.condition.config.WeightConditionDao
import org.koin.core.context.loadKoinModules
import org.koin.core.context.startKoin
import org.koin.core.module.dsl.named
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

class ModulesLoad {
    val dbModules = module {
        single { SqliteJdbcProvider(DefDBUrl).jdbcTemplate }
        singleOf(::GroupStrategyDao)
        singleOf(::ComboInfoDao)
        singleOf(::CardInfoDao)
        singleOf(::WeightConditionDao)
    }
    val parseCardWeightInfoModule = module {

        singleOf(::ParseCombo) bind ParseCardWeightInfo::class

        //todo-future 暂挂
        singleOf(::LieRenParse) bind ParseCardWeightInfo::class
    }

    val comBoInfoModule = module {
        singleOf(::LastUseCombo) { named(LAST) } bind ComboParse::class
        singleOf(::ComboImpl) { named(DEF) } bind ComboParse::class
        singleOf(::ComboImpl) { named(BEFORE) } bind ComboParse::class
        singleOf(::ChangeComboParse) { named(CHANGE) } bind ComboParse::class
        singleOf(::FirstUseCombo) { named(FIRST) } bind ComboParse::class
    }

    val mainModule = module {
        single { WAR }
        singleOf(::MyWarManage) bind WarInfo::class
        singleOf(::WeightHandlerDomain)
    }
    val configHandler = module {
        singleOf(::UseConfigHandler) bind ConfigHandler::class

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

        single<ConfigDispatcher> { ConfigDispatcher(getAll(), getAll()) }
        single<RuleInfoRegister> {
            val infos = get<Map<String, CardWeightInfo>>(named("weightInfo")).values
            RuleInfoRegister(infos, get())
        }
        singleOf(::RuleRegistry)
    }

    val utilsModule = module {
        singleOf(::UseDomain)
        singleOf(::FindPlanner)
        singleOf(::CleanWarUtils)

    }
    val findStrategy = module {
        singleOf(::ExtCostStrategy) bind FindComboStrategy::class
        //singleOf(::ForgeFindStrategy) bind FindComboStrategy::class
        singleOf(::DefFindStrategy) bind FindComboStrategy::class
        singleOf(::SkillFindStrategy) bind FindComboStrategy::class

    }


    fun loadModules() {
        startKoin {
            modules(
                mainModule,
                dbModules,
                parseCardWeightInfoModule,
                comBoInfoModule,
                utilsModule,
                findStrategy,
                configHandler
            )
            modules(module { singleOf(::LifecycleRegisterImpl) bind LifecycleRegister::class })
            val extraModule = ServiceLoaderUtils.loadServices(ModulesInfo::class.java)
            extraModule.forEach {
                loadKoinModules(it.loadModules())
            }
        }

    }


}
