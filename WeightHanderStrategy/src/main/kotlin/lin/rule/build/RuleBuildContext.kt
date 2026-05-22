package lin.rule.build

import lin.bean.CardWeightInfo
import lin.rule.parse.mapToRuleArgs
import lin.rule.tree.EvaluatorLeafConfig
import kotlin.reflect.KClass

typealias ContextualRuleSpec<T> = RuleBuildContext<T>.() -> RuleLogic
typealias RuleConfigParse = (List<Double>) -> List<CardWeightInfo>

class BuildRuleFactory(infoMap: Map<String, CardWeightInfo>) {
    private val parse by lazy { ruleConfigParse(infoMap) }

    fun <T : Any> build(
        parameterType: KClass<T>,
        spec: ContextualRuleSpec<T>
    ): RuleBuilder<T> {
        val factory: RuleFactory<T> = { leafConfig, params ->
            val ctx = RuleBuildContext(leafConfig, params, parse)
            ctx.spec()
        }
        return RuleBuilder(parameterType).factory(factory)
    }
}

class RuleBuildContext<T : Any>(
    val leafConfig: EvaluatorLeafConfig,
    val params: T,
    val ruleConfigParse: RuleConfigParse
) {
    fun <U : Any> parseArgs(parameterType: KClass<U>): U =
        mapToRuleArgs(leafConfig.args, parameterType)

    inline fun <reified U : Any> parseArgs(): U = parseArgs(U::class)
}

fun ruleConfigParse(infoMap: Map<String, CardWeightInfo>): RuleConfigParse {
    val infoByGroupId = infoMap.values.groupBy { it.groupId }
    return { groupIds ->
        val depWeightInfos = mutableListOf<CardWeightInfo>()
        groupIds.forEach { depId ->
            infoByGroupId[depId]?.run {
                depWeightInfos.addAll(this)
            }
        }
        depWeightInfos
    }
}
