package lin.rule.condition.orthogonal.spi

import lin.rule.condition.orthogonal.*

/**
 * 内置数据源的 SPI 提供者。
 */
class DefaultDataSourceProvider : DataSourceProvider {
    override fun get(): Collection<DataSource<*>> = listOf(
        HandCardCountSource,
        BattlefieldRacesSource
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
