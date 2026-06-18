package lin.rule.condition

import com.fasterxml.jackson.databind.ObjectMapper
import lin.rule.orthogonal.DataSource
import lin.rule.orthogonal.Operator
import lin.rule.orthogonal.Transform
import lin.rule.parse.SpecValidator
import kotlin.reflect.KType
import kotlin.reflect.full.isSubtypeOf

/**
 * 动态管道流条件装配器：负责在编译期将 PipelineRef 装配为具体的可运行 ConditionLogic 闭包。
 */
class PipelineAssembler(
    private val dataSources: Map<String, DataSource<*>>,
    private val transforms: Map<String, Transform<*, *>>,
    private val operators: Map<String, Operator<*, *>>,
    private val objectMapper: ObjectMapper
) {

    fun findDataSource(id: String): DataSource<*>? = dataSources[id]
    fun findTransform(id: String): Transform<*, *>? = transforms[id]
    fun findOperator(id: String): Operator<*, *>? = operators[id]

    fun allDataSources(): Collection<DataSource<*>> = dataSources.values
    fun allTransforms(): Collection<Transform<*, *>> = transforms.values
    fun allOperators(): Collection<Operator<*, *>> = operators.values

    /**
     * 将 PipelineRef 动态装配为可运行的 ConditionLogic 闭包。
     * 核心步骤包括：
     * 1. 查找数据源、各级转换器与判定算子。
     * 2. 进行编译装配期级联强类型契约匹配校验（利用 KType.isSubtypeOf 支持协变）。
     * 3. 校验各级参数，装配为链式执行闭包。
     */
    fun assemble(ref: ConditionPayload.PipelineRef): ConditionLogic {
        val source = dataSources[ref.sourceId]
            ?: throw IllegalArgumentException("DataSource not found: ${ref.sourceId}")

        var currentType: KType = source.outputType

        // 1. 逐级验证 Transform 的参数与类型兼容性
        val transformInstances = ref.transforms.map { call ->
            @Suppress("UNCHECKED_CAST")
            val transform = transforms[call.transformId] as? Transform<Any, Any>
                ?: throw IllegalArgumentException("Transform not found: ${call.transformId}")

            // 级联类型检查
            require(currentType.isSubtypeOf(transform.inputType)) {
                "Type mismatch: previous output type [$currentType] is not compatible with Transform [${transform.id}] input type [${transform.inputType}]"
            }

            // 参数校验
            val validation = SpecValidator.validate(call.args, transform.fields)
            if (!validation.isValid) {
                throw IllegalArgumentException("转换器 [${transform.id}] 参数校验失败: ${validation.errors.joinToString { it.message }}")
            }

            currentType = transform.outputType
            transform to call.args
        }

        // 2. 验证判定算子
        @Suppress("UNCHECKED_CAST")
        val operator = operators[ref.operatorId] as? Operator<Any, Any>
            ?: throw IllegalArgumentException("Operator not found: ${ref.operatorId}")

        // 判定算子输入类型检查
        require(currentType.isSubtypeOf(operator.inputType)) {
            "Type mismatch: final pipeline output type [$currentType] is not compatible with Operator [${operator.id}] input type [${operator.inputType}]"
        }

        // 算子参数校验
        val operatorValidation = SpecValidator.validate(ref.operatorArgs, operator.paramSpecs)
        if (!operatorValidation.isValid) {
            throw IllegalArgumentException("动态条件 [${ref.refId}] 算子参数校验失败: ${operatorValidation.errors.joinToString { it.message }}")
        }

        // 统一在编译装配期，将 Map 参数转换为算子声明的强类型数据类
        val parsedOperatorParameter = try {
            objectMapper.convertValue(ref.operatorArgs, operator.parameterType.java)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid arguments for Operator [${ref.operatorId}]: ${e.message}", e)
        }

        // 3. 构建闭包
        return { // context(RuleEnv) RuleContext.() -> Boolean
            var currentVal: Any = source.resolve(this)

            for ((transform, args) in transformInstances) {
                currentVal = transform.transform(currentVal, this, args)
            }

            operator.evaluate(currentVal, parsedOperatorParameter)
        }
    }
}
