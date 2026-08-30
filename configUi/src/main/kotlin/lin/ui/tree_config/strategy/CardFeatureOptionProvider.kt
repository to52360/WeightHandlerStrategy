package lin.ui.tree_config.strategy

import lin.rule.build.DynamicFieldOption
import lin.rule.orthogonal.CardFeature
import lin.serviceLoader.provider.SelectOptionProvider

/**
 * `has_card_feature` 算子的 feature 字段可选值（T-031）。
 *
 * 直接取自 [CardFeature] 枚举：显示名中文、value 用枚举名，
 * 与 CardRaceOptionProvider 的 card_races 模式一致。
 */
class CardFeatureOptionProvider : SelectOptionProvider {
    override val dataSourceId: String = "card_features"

    override fun getOptions(): List<DynamicFieldOption> =
        CardFeature.entries.map { feature ->
            DynamicFieldOption(label = feature.displayName, value = feature.name)
        }
}
