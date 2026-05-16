package lin.di

import lin.serviceLoader.parse.LieRenParse
import lin.serviceLoader.parse.ParseCardWeightInfo
import lin.serviceLoader.parse.ParseCombo
import lin.utils.database.SqliteJdbcProvider
import lin.utils.database.dao.CardInfoDao
import lin.weightHandler.condition.config.ComboInfoDao
import lin.weightHandler.condition.config.GroupStrategyDao
import lin.weightHandler.condition.config.WeightConditionDao
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

private val dbModules = module {
    single { SqliteJdbcProvider() }
    single { get<SqliteJdbcProvider>().jdbcTemplate }
    singleOf(::GroupStrategyDao)
    singleOf(::ComboInfoDao)
    singleOf(::CardInfoDao)
    singleOf(::WeightConditionDao)
}

private val parseCardWeightInfoModule = module {
    singleOf(::ParseCombo) bind ParseCardWeightInfo::class
    //todo-future 暂挂
    singleOf(::LieRenParse) bind ParseCardWeightInfo::class
}

val dataModule = module {
    includes(dbModules, parseCardWeightInfoModule)
}
