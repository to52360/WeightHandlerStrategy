package lin.rule.condition

import lin.myLog
import lin.rule.condition.orthogonal.ConditionAssembler
import lin.rule.parse.RuleFieldSpec
import lin.rule.tree.EvaluatorLeafSourceType
import lin.rule.tree.EvaluatorLeafUiItem
import lin.serviceLoader.provider.ConditionRegistrationProvider

data class ConditionUiItem(
    val conditionId: String,
    val name: String?,
    val desc: String?,
    val fields: List<ConditionFieldUiItem>
)

data class ConditionFieldUiItem(
    val propertyName: String,
    val name: String,
    val description: String,
    val ruleFieldSpec: RuleFieldSpec
)

class ConditionRegistry(
    providers: Collection<ConditionRegistrationProvider>,
    private val conditionAssembler: ConditionAssembler? = null
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

    fun uiItems(): List<ConditionUiItem> {
        return registrationsById.values.map { registration ->
            ConditionUiItem(
                conditionId = registration.conditionId,
                name = registration.metadata?.name,
                desc = registration.metadata?.desc,
                fields = listOf(
                    ConditionFieldUiItem(
                        propertyName = registration.field.propertyName,
                        name = registration.field.name,
                        description = registration.field.description,
                        ruleFieldSpec = registration.field.toRuleFieldSpec()
                    )
                )
            )
        }
    }

    fun leafUiItems(): List<EvaluatorLeafUiItem> {
        return registrationsById.values.map { registration ->
            EvaluatorLeafUiItem(
                sourceType = EvaluatorLeafSourceType.CONDITION,
                sourceId = registration.conditionId,
                name = registration.metadata?.name,
                desc = registration.metadata?.desc,
                fields = listOf(registration.field.toRuleFieldSpec())
            )
        }
    }

    fun build(conditionId: String, args: Map<String, Any>): ConditionLogic {
        val registration = require(conditionId)
        @Suppress("UNCHECKED_CAST")
        return (registration as ConditionRegistration<Any>).build(args)
    }

    fun build(conditionRef: ConditionPayload.ConditionRef): ConditionLogic {
        // ARCH-PLACEHOLDER(orthogonal-condition, P-004): 拦截 dynamic_ 前缀的动态组合条件 | replace-with: 完善动态组合条件在 Registry 的动态反序列化/元数据注册集成
        if (conditionRef.conditionId.startsWith("dynamic_")) {
            val assembler = conditionAssembler ?: error("ConditionAssembler is not configured in this context")
            return assembler.assemble(conditionRef)
        }
        return build(conditionRef.conditionId, conditionRef.args)
    }
}
