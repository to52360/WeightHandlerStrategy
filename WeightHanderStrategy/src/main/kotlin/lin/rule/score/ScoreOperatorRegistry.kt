package lin.rule.score

import lin.myLog
import lin.serviceLoader.provider.ScoreOperatorProvider

class ScoreOperatorRegistry(
    providers: Collection<ScoreOperatorProvider>
) {
    private val operatorsById: Map<String, ScoreOperator<*, *>>

    init {
        val operatorMap = linkedMapOf<String, ScoreOperator<*, *>>()
        providers.forEach { provider ->
            provider.getScoreOperators().forEach { operator ->
                val previous = operatorMap.putIfAbsent(operator.id, operator)
                require(previous == null) {
                    "Duplicate ScoreOperator id=${operator.id}, provider=${provider::class.java.name}"
                }
            }
        }
        operatorsById = operatorMap
        myLog.info { "loaded ScoreOperator ids=${operatorsById.keys}" }
    }

    fun all(): List<ScoreOperator<*, *>> = operatorsById.values.toList()

    fun find(id: String): ScoreOperator<*, *>? = operatorsById[id]

    fun require(id: String): ScoreOperator<*, *> {
        return find(id) ?: throw IllegalArgumentException("ScoreOperator not found: id=$id")
    }
}
