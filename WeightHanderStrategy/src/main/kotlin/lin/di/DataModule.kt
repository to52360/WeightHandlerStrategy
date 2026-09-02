package lin.di

import lin.serviceLoader.parse.LieRenParse
import lin.serviceLoader.parse.ParseCardWeightInfo
import lin.utils.database.SqliteJdbcProvider
import lin.utils.database.dao.CardInfoDao
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

private val dbModules = module {
    single { SqliteJdbcProvider() }
    single { get<SqliteJdbcProvider>().jdbcTemplate }
    singleOf(::CardInfoDao)
}

private val parseCardWeightInfoModule = module {
    //todo-future 暂挂
    singleOf(::LieRenParse) bind ParseCardWeightInfo::class
}

val dataModule = module {
    includes(dbModules, parseCardWeightInfoModule)
}
