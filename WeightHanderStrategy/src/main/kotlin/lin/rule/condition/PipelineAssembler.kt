package lin.rule.condition

import com.fasterxml.jackson.databind.ObjectMapper
import lin.rule.orthogonal.DataSource
import lin.rule.orthogonal.Operator
import lin.rule.orthogonal.Transform
import lin.rule.parse.SpecValidator
import lin.rule.parse.ValidationError
import lin.rule.parse.ValidationResult
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
     * 校验 PipelineRef 的完整性：dataSource/transform/operator 存在性、类型链兼容性、参数合法性。
     * 供 assemble 内部和外部验证器共用。
     */
    fun validatePipelineRef(ref: ConditionPayload.PipelineRef): ValidationResult {
        val errors = mutableListOf<ValidationError>()

        val source = dataSources[ref.sourceId]
        if (source == null) {
            errors += ValidationError(ref.sourceId, "PIPELINE_UNKNOWN_SOURCE", "DataSource not found: ${ref.sourceId}")
            return ValidationResult(errors)
        }

        var currentType: KType = source.outputType

        for (call in ref.transforms) {
            @Suppress("UNCHECKED_CAST")
            val transform = transforms[call.transformId] as? Transform<Any, Any>
            if (transform == null) {
                errors += ValidationError(
                    call.transformId,
                    "PIPELINE_UNKNOWN_TRANSFORM",
                    "Transform not found: ${call.transformId}"
                )
                continue
            }
            if (!currentType.isSubtypeOf(transform.inputType)) {
                errors += ValidationError(
                    call.transformId, "PIPELINE_TYPE_MISMATCH",
                    "Type mismatch: previous output [$currentType] not compatible with Transform [${transform.id}] input [${transform.inputType}]"
                )
            }
            errors += SpecValidator.validate(call.args, transform.fields).errors
            currentType = transform.outputType
        }

        @Suppress("UNCHECKED_CAST")
        val operator = operators[ref.operatorId] as? Operator<Any, Any>
        if (operator == null) {
            errors += lin.rule.parse.ValidationError(
                ref.operatorId,
                "PIPELINE_UNKNOWN_OPERATOR",
                "Operator not found: ${ref.operatorId}"
            )
        } else {
            if (!currentType.isSubtypeOf(operator.inputType)) {
                errors += ValidationError(
                    ref.operatorId, "PIPELINE_TYPE_MISMATCH",
                    "Type mismatch: pipeline output [$currentType] not compatible with Operator [${operator.id}] input [${operator.inputType}]"
                )
            }
            errors += SpecValidator.validate(ref.operatorArgs, operator.paramSpecs).errors
        }

        return ValidationResult(errors)
    }

    /**
     * 将 PipelineRef 动态装配为可运行的 ConditionLogic 闭包。
     * 内部先调 validatePipelineRef 做前置校验，通过后再装配。
     */
    fun assemble(ref: ConditionPayload.PipelineRef): ConditionLogic {
        val validation = validatePipelineRef(ref)
        if (!validation.isValid) {
            throw IllegalArgumentException("PipelineRef [${ref.refId}] 校验失败: ${validation.errors.joinToString { it.message }}")
        }

        val source = dataSources[ref.sourceId]!!
        var currentType: KType = source.outputType

        val transformInstances = ref.transforms.map { call ->
            @Suppress("UNCHECKED_CAST")
            val transform = transforms[call.transformId] as Transform<Any, Any>
            currentType = transform.outputType
            transform to call.args
        }

        @Suppress("UNCHECKED_CAST")
        val operator = operators[ref.operatorId] as Operator<Any, Any>

        val parsedOperatorParameter = try {
            objectMapper.convertValue(ref.operatorArgs, operator.parameterType.java)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid arguments for Operator [${ref.operatorId}]: ${e.message}", e)
        }

        return { // context(RuleEnv) RuleContext.() -> Boolean
            var currentVal: Any = source.resolve(this)

            for ((transform, args) in transformInstances) {
                currentVal = transform.transform(currentVal, this, args)
            }

            operator.evaluate(currentVal, parsedOperatorParameter)
        }
    }
}
