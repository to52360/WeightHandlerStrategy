package lin.serviceLoader.provider

import lin.rule.score.ScoreOperator

interface ScoreOperatorProvider {
    fun getScoreOperators(): Collection<ScoreOperator<*, *>>
}
