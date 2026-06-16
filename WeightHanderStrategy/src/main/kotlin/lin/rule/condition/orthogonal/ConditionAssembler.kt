package lin.rule.condition.orthogonal

import com.fasterxml.jackson.databind.ObjectMapper
import lin.rule.condition.ConditionLogic
import lin.rule.condition.ConditionPayload

/**
 * 动态条件装配器：负责在编译期将 OrthogonalRef 装配为具体的可运行 ConditionLogic 闭包。
 */
class ConditionAssembler(
    private val dataSources: Map<String, DataSource<*>>,
    private val operators: Map<String, Operator<*, *>>,
    private val objectMapper: ObjectMapper
) {

    fun findDataSource(id: String): DataSource<*>? = dataSources[id]
    fun findOperator(id: String): Operator<*, *>? = operators[id]

    fun allDataSources(): Collection<DataSource<*>> = dataSources.values
    fun allOperators(): Collection<Operator<*, *>> = operators.values

    /**
     * 将 OrthogonalRef 动态装配为可运行的 ConditionLogic 闭包。
     * 核心步骤包括：
     * 1. 查找数据源与算子定义。
     * 2. 进行编译期强类型契约匹配校验。
     * 3. 统一集中反序列化并校验算子参数，如有参数缺失或类型异常，在此阶段立即抛出。
     */
    fun assemble(ref: ConditionPayload.OrthogonalRef): ConditionLogic {
        val source = dataSources[ref.sourceId]
            ?: throw IllegalArgumentException("DataSource not found: ${ref.sourceId}")

        @Suppress("UNCHECKED_CAST")
        val operator = operators[ref.operatorId] as? Operator<Any, Any>
            ?: throw IllegalArgumentException("Operator not found: ${ref.operatorId}")

        // 强类型兼容性校验
        require(operator.inputType == source.outputType) {
            "Type mismatch: DataSource [${source.id}] output type [${source.outputType}] is not compatible with Operator [${operator.id}] input type [${operator.inputType}]"
        }

        // 分拣参数：属于数据源的，和属于算子的
        val sourceFieldNames = source.fields.map { it.propertyName }.toSet()
        val sourceArgs = ref.args.filterKeys { it in sourceFieldNames }
        val operatorArgs = ref.args.filterKeys { it !in sourceFieldNames }

        // 校验数据源参数
        val sourceValidation = lin.rule.parse.SpecValidator.validate(sourceArgs, source.fields)
        if (!sourceValidation.isValid) {
            throw IllegalArgumentException("数据源 [${source.id}] 参数校验失败: ${sourceValidation.errors.joinToString { it.message }}")
        }

        // 校验算子参数
        val operatorValidation = lin.rule.parse.SpecValidator.validate(operatorArgs, operator.paramSpecs)
        if (!operatorValidation.isValid) {
            lin.myLog.error { "动态条件 [${ref.refId}] 算子参数校验失败: ${operatorValidation.errors.joinToString { it.message }}" }
            throw IllegalArgumentException("动态条件 [${ref.refId}] 校验失败: ${operatorValidation.errors.joinToString { it.message }}")
        }

        // 统一在编译装配期，将 Map 参数转换为算子声明的强类型数据类
        val parsedParameter = try {
            objectMapper.convertValue(operatorArgs, operator.parameterType.java)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid arguments for Operator [${ref.operatorId}]: ${e.message}", e)
        }

        return { // context(RuleEnv) RuleContext.() -> Boolean
            val input = source.resolve(this, sourceArgs)
            operator.evaluate(input, parsedParameter)
        }
    }
}

