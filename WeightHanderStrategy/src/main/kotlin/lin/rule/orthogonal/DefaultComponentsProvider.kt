package lin.rule.orthogonal

import lin.serviceLoader.provider.DataSourceProvider
import lin.serviceLoader.provider.OperatorProvider
import lin.serviceLoader.provider.TransformProvider

/**
 * 管道流默认数据源的 SPI 提供者。
 */
class DefaultDataSourceProvider : DataSourceProvider {
    override fun get(): Collection<DataSource<*>> = listOf(
        MeBoardCardsSource,
        RivalBoardCardsSource,
        BoardCardsSource,
        HandCardsSource
    )
}

/**
 * 管道流默认转换器的 SPI 提供者。
 */
class DefaultTransformProvider : TransformProvider {
    override fun get(): Collection<Transform<*, *>> = listOf(
        RaceFilterTransform,
        CountProjectionTransform
    )
}

/**
 * 管道流默认判定算子的 SPI 提供者。
 */
class DefaultOperatorProvider : OperatorProvider {
    override fun get(): Collection<Operator<*, *>> = listOf(
        GreaterThanOrEqualOp,
        ContainsRaceOp,
        EqualOp,
        LessThanOp
    )
}
