package lin.tree_config.ui.strategy

import lin.rule.build.DynamicFieldOption
import lin.rule.score.DefaultScoreOperators
import lin.serviceLoader.provider.SelectOptionProvider

class ScoreOperatorOptionProvider : SelectOptionProvider {
    override val dataSourceId: String = "score_operators"

    override fun getOptions(): List<DynamicFieldOption> {
        return DefaultScoreOperators.all.map { (id, operator) ->
            DynamicFieldOption(label = operator.name, value = id)
        }
    }
}
