package lin.ui.tree_config.ui.strategy

import lin.rule.build.DynamicFieldOption
import lin.rule.score.ScoreOperatorRegistry
import lin.serviceLoader.provider.SelectOptionProvider
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class ScoreOperatorOptionProvider : SelectOptionProvider, KoinComponent {
    override val dataSourceId: String = "score_operators"

    private val scoreOperatorRegistry: ScoreOperatorRegistry by inject()

    override fun getOptions(): List<DynamicFieldOption> {
        return scoreOperatorRegistry.all().map { operator ->
            DynamicFieldOption(label = operator.name, value = operator.id)
        }
    }
}
