package lin.rule.condition.orthogonal

import com.fasterxml.jackson.databind.ObjectMapper
import lin.rule.condition.ConditionLogic
import lin.rule.condition.ConditionPayload

/**
 * 动态条件装配器：负责在编译期将 ConditionRef 装配为具体的可运行 ConditionLogic 闭包。
 */
class ConditionAssembler(
    private val dataSources: Map<String, DataSource<*>>,
    private val operators: Map<String, Operator<*, *>>,
    private val objectMapper: ObjectMapper
) {

    fun findDataSource(id: String): DataSource<*>? = dataSources[id]
    fun findOperator(id: String): Operator<*, *>? = operators[id]

    /**
     * 将 ConditionRef 动态装配为可运行的 ConditionLogic 闭包。
     * 核心步骤包括：
     * 1. 查找数据源与算子定义。
     * 2. 进行编译期强类型契约匹配校验。
     * 3. 统一集中反序列化并校验算子参数，如有参数缺失或类型异常，在此阶段立即抛出。
     */
    fun assemble(ref: ConditionPayload.ConditionRef): ConditionLogic {
        val sourceId = ref.args["_sourceId"] as? String
            ?: throw IllegalArgumentException("Missing required parameter: _sourceId")
        val operatorId = ref.args["_operatorId"] as? String
            ?: throw IllegalArgumentException("Missing required parameter: _operatorId")

        val source = dataSources[sourceId]
            ?: throw IllegalArgumentException("DataSource not found: $sourceId")

        @Suppress("UNCHECKED_CAST")
        val operator = operators[operatorId] as? Operator<Any, Any>
            ?: throw IllegalArgumentException("Operator not found: $operatorId")

        // 强类型兼容性校验
        // ARCH-PLACEHOLDER(orthogonal-condition, P-003): 校验逻辑待优化以支持更复杂的泛型与子类兼容 | replace-with: 优化为 assignment-compatible 校验，而仅是相等校验
        require(operator.inputType == source.outputType) {
            "Type mismatch: DataSource [${source.id}] output type [${source.outputType}] is not compatible with Operator [${operator.id}] input type [${operator.inputType}]"
        }

        // 统一在编译装配期，将 Map 参数转换为算子声明的强类型数据类，实现早期的参数完整性与类型校验
        val parsedParameter = try {
            objectMapper.convertValue(ref.args, operator.parameterType.java)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid arguments for Operator [${operatorId}]: ${e.message}", e)
        }

        return { // context(RuleEnv) RuleContext.() -> Boolean
            val input = source.resolve(this)
            operator.evaluate(input, parsedParameter)
        }
    }
}
