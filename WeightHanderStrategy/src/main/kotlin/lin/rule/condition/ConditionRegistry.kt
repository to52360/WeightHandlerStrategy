package lin.rule.condition

import lin.myLog
import lin.rule.parse.FieldSpec
import lin.rule.tree.CONDITION_BUILT_IN_FIELDS
import lin.rule.tree.EvaluatorLeafKind
import lin.rule.tree.EvaluatorLeafMeta
import lin.serviceLoader.provider.ConditionRegistrationProvider

data class ConditionMeta(
    val conditionId: String,
    val name: String?,
    val desc: String?,
    val fields: List<ConditionFieldMeta>
)

data class ConditionFieldMeta(
    val propertyName: String,
    val name: String,
    val description: String,
    val fieldSpec: FieldSpec
)

class ConditionRegistry(
    providers: Collection<ConditionRegistrationProvider>,
    val pipelineAssembler: PipelineAssembler? = null
) {
    private val registrationsById: Map<String, ConditionRegistration<*>>

    init {
        val registrationMap = linkedMapOf<String, ConditionRegistration<*>>()
        providers.forEach { provider ->
            provider.getConditionRegistrations().forEach { registration ->
                val previous = registrationMap.putIfAbsent(registration.conditionId, registration)
                require(previous == null) {
                    "Duplicate ConditionRegistration conditionId=${registration.conditionId}, provider=${provider::class.java.name}"
                }
            }
        }
        registrationsById = registrationMap
        myLog.info { "loaded ConditionRegistration ids=${registrationsById.keys}" }
    }

    fun all(): List<ConditionRegistration<*>> = registrationsById.values.toList()

    fun find(conditionId: String): ConditionRegistration<*>? = registrationsById[conditionId]

    fun require(conditionId: String): ConditionRegistration<*> {
        return find(conditionId)
            ?: throw ConditionBuildException("ConditionRegistration not found: conditionId=$conditionId")
    }

    fun metadataList(): List<ConditionMeta> {
        return registrationsById.values.map { registration ->
            ConditionMeta(
                conditionId = registration.conditionId,
                name = registration.metadata?.name,
                desc = registration.metadata?.desc,
                fields = listOf(
                    ConditionFieldMeta(
                        propertyName = registration.field.propertyName,
                        name = registration.field.name,
                        description = registration.field.description,
                        fieldSpec = registration.field.toFieldSpec()
                    )
                )
            )
        }
    }

    fun leafMetas(): List<EvaluatorLeafMeta> {
        return registrationsById.values.map { registration ->
            EvaluatorLeafMeta(
                kind = EvaluatorLeafKind.Condition.Plain,
                sourceId = registration.conditionId,
                name = registration.metadata?.name ?: registration.conditionId,
                desc = registration.metadata?.desc ?: "",
                builtInFields = CONDITION_BUILT_IN_FIELDS,
                fields = listOf(registration.field.toFieldSpec())
            )
        }
    }

    fun build(conditionId: String, args: Map<String, Any>): ConditionLogic {
        val registration = require(conditionId)
        @Suppress("UNCHECKED_CAST")
        return (registration as ConditionRegistration<Any>).build(args)
    }

    fun build(payload: ConditionPayload): ConditionLogic {
        return when (payload) {
            is ConditionPayload.ConditionRef ->
                build(payload.conditionId, payload.args)

            is ConditionPayload.PipelineRef -> {
                val assembler = pipelineAssembler ?: error("PipelineAssembler is not configured in this context")
                assembler.assemble(payload)
            }
        }
    }
}
