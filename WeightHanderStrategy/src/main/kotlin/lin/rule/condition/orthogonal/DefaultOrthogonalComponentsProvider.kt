package lin.rule.condition.orthogonal

import lin.serviceLoader.provider.DataSourceProvider
import lin.serviceLoader.provider.OperatorProvider

/**
 * 内置数据源的 SPI 提供者。
 */
class DefaultDataSourceProvider : DataSourceProvider {
    override fun get(): Collection<DataSource<*>> = listOf(
        HandCardCountSource,
        BattlefieldRacesSource,
        MinionsCountSource
    )
}

/**
 * 内置算子的 SPI 提供者。
 */
class DefaultOperatorProvider : OperatorProvider {
    override fun get(): Collection<Operator<*, *>> = listOf(
        GreaterThanOrEqualOp,
        ContainsRaceOp
    )
}
