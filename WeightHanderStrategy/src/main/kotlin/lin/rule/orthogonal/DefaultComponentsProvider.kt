package lin.rule.orthogonal

import lin.serviceLoader.provider.DataSourceProvider
import lin.serviceLoader.provider.OperatorProvider
import lin.serviceLoader.provider.TransformProvider

/**
 * 管道流默认数据源的 SPI 提供者。
 */
class DefaultDataSourceProvider : DataSourceProvider {
    override fun get(): Collection<DataSource<*>> = listOf(
        WarViewSource,
        MeBoardCardsSource,
        RivalBoardCardsSource,
        BoardCardsSource,
        HandCardsSource,
        HandComboCardsSource,
        MeComboCardsSource,
        MyGraveyardCardsSource,
        MyHeroHealthSource,
        MyManaCrystalSource,
        EvaluatingCardSource,
        MatchGroupPlayedCountsSource,
        MatchActivityEventsSource,
        MatchTurnCountSource
    )
}

/**
 * 管道流默认转换器的 SPI 提供者。
 */
class DefaultTransformProvider : TransformProvider {
    override fun get(): Collection<Transform<*, *>> = listOf(
        ExcessDamageTransform,
        AcceptableAttackTransform,
        RivalCardsFromViewTransform,
        MeCardsFromViewTransform,
        RaceFilterTransform,
        CardTypeFilterTransform,
        GroupFilterTransform,
        PurposeFilterTransform,
        TauntFilterTransform,
        CountProjectionTransform,
        SumAttackTransform,
        SumHealthTransform,
        EvaluatingCardCostTransform,
        ToCardsTransform,
        PickGroupCountTransform,
        WeightedActivitySumTransform
    )
}

/**
 * 管道流默认判定算子的 SPI 提供者。
 */
class DefaultOperatorProvider : OperatorProvider {
    override fun get(): Collection<Operator<*, *>> = listOf(
        GreaterThanOrEqualOp,
        GreaterThanOp,
        LessThanOrEqualOp,
        LessThanOp,
        EqualOp,
        NotEqualOp,
        IsEmptyOp,
        IsNotEmptyOp,
        IsCardTypeOp,
        CardRaceMatchOp,
        CardBelongsToGroupOp,
        CardHasPurposeTagOp,
        ContainsRaceOp
    )
}
