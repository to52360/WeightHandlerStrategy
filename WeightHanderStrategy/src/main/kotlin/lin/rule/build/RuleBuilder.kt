package lin.rule.build

import lin.bean.ComboCard
import lin.domain.WarInfo
import lin.rule.handler.IntentResult
import lin.rule.tree.RuleConfig
import kotlin.reflect.KClass

typealias RuleLogic = (callCard: ComboCard, warInfo: WarInfo) -> IntentResult

typealias RuleFactory<T> = (RuleConfig, T) -> RuleLogic

class RuleBuilder<T : Any>(
    private val parameterType: KClass<T>
) {
    private lateinit var id: String
    private lateinit var factory: RuleFactory<T>
    private var metadata: RuleMetadata? = null
        get() {
            if (field == null) field = RuleMetadata(id, id)
            return field!!
        }

    fun id(id: String) = apply { this.id = id }
    fun factory(factory: RuleFactory<T>) = apply { this.factory = factory }
    fun metadata(metadata: RuleMetadata) = apply { this.metadata = metadata }
    fun metadata(name: String, desc: String? = null) = apply { this.metadata = RuleMetadata(name, desc) }

    fun extraField() = apply {
        TODO()
    }



    fun build(): RuleRegistration<T> {
        return RuleRegistration(
            ruleId = id,
            spec = RuleSpec(
                parameterType = parameterType,
                ruleFactory = factory
            ),
            metadata = metadata
        )
    }
}






