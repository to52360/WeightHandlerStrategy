package lin.rule.condition

import lin.rule.build.RuleMetadata
import lin.rule.context.RuleContext
import lin.rule.context.RuleEnv
import lin.rule.parse.FieldConstraint
import lin.rule.parse.FieldSpec
import lin.rule.parse.FieldType

typealias ConditionLogic = RuleContext.(RuleEnv) -> Boolean

typealias ConditionFactory<T> = (T) -> ConditionLogic

class ConditionBuildException(message: String) : IllegalArgumentException(message)

sealed interface ConditionType<T : Any> {
    data object StringType : ConditionType<String>
    data object IntType : ConditionType<Int>
    data object BooleanType : ConditionType<Boolean>
    data object DoubleType : ConditionType<Double>

    fun toFieldType(): FieldType {
        return when (this) {
            StringType -> FieldType.StringType
            IntType -> FieldType.IntType
            BooleanType -> FieldType.BooleanType
            DoubleType -> FieldType.DoubleType
        }
    }
}

sealed interface ConditionValue {
    data class Scalar<T : Any>(
        val type: ConditionType<T>
    ) : ConditionValue

    data class ListValue<T : Any>(
        val elementType: ConditionType<T>
    ) : ConditionValue

    fun toFieldType(selectDataSourceId: String?): FieldType {
        fun wrapSelect(fieldType: FieldType): FieldType {
            return if (selectDataSourceId.isNullOrEmpty()) fieldType
            else FieldType.SelectType(selectDataSourceId, fieldType)
        }

        return when (this) {
            is Scalar<*> -> wrapSelect(type.toFieldType())
            is ListValue<*> -> FieldType.ListType(wrapSelect(elementType.toFieldType()))
        }
    }
}

data class ConditionFieldDef<T>(
    val propertyName: String,
    val name: String,
    val description: String = "",
    val value: ConditionValue,
    val constraints: List<FieldConstraint> = listOf(FieldConstraint.Required),
    val selectDataSourceId: String? = null
) {
    fun select(dataSourceId: String): ConditionFieldDef<T> = copy(selectDataSourceId = dataSourceId)

    fun toFieldSpec(): FieldSpec {
        return FieldSpec(
            propertyName = propertyName,
            name = name,
            description = description,
            typeStruct = value.toFieldType(selectDataSourceId),
            constraints = constraints
        )
    }

    @Suppress("UNCHECKED_CAST")
    fun parseValue(args: Map<String, Any>): T {
        val rawValue = args[propertyName]
            ?: throw ConditionBuildException("Missing condition arg: propertyName=$propertyName")

        return when (value) {
            is ConditionValue.Scalar<*> -> parseScalar(rawValue, value.type) as T
            is ConditionValue.ListValue<*> -> parseList(rawValue, value.elementType) as T
        }
    }

    private fun parseScalar(rawValue: Any, type: ConditionType<*>): Any {
        return when (type) {
            ConditionType.StringType -> rawValue as? String
            ConditionType.IntType -> when (rawValue) {
                is Int -> rawValue
                is Number -> rawValue.toInt()
                else -> null
            }

            ConditionType.BooleanType -> rawValue as? Boolean
            ConditionType.DoubleType -> when (rawValue) {
                is Double -> rawValue
                is Number -> rawValue.toDouble()
                else -> null
            }
        } ?: throw ConditionBuildException(
            "Invalid condition arg type: propertyName=$propertyName, expected=$type, actual=${rawValue::class}"
        )
    }

    private fun parseList(rawValue: Any, elementType: ConditionType<*>): List<Any> {
        val rawList = rawValue as? List<*>
            ?: throw ConditionBuildException(
                "Invalid condition arg type: propertyName=$propertyName, expected=List, actual=${rawValue::class}"
            )

        return rawList.mapIndexed { index, item ->
            item ?: throw ConditionBuildException(
                "Invalid condition arg value: propertyName=$propertyName, index=$index, value=null"
            )
            try {
                parseScalar(item, elementType)
            } catch (ex: ConditionBuildException) {
                throw ConditionBuildException(
                    "Invalid condition arg list item: propertyName=$propertyName, index=$index, reason=${ex.message}"
                )
            }
        }
    }
}

data class ConditionRegistration<T>(
    val conditionId: String,
    val metadata: RuleMetadata?,
    val field: ConditionFieldDef<T>,
    val conditionFactory: ConditionFactory<T>
) {
    fun build(args: Map<String, Any>): ConditionLogic {
        return conditionFactory(field.parseValue(args))
    }
}

class ConditionBuilder<T> private constructor(
    private val value: ConditionValue
) {
    private lateinit var id: String
    private lateinit var factory: ConditionFactory<T>
    private var metadata: RuleMetadata? = null
        get() {
            if (field == null) field = RuleMetadata(id, id)
            return field!!
        }

    private var field: ConditionFieldDef<T>? = null

    fun id(id: String) = apply { this.id = id }

    fun factory(factory: ConditionFactory<T>) = apply { this.factory = factory }

    fun metadata(metadata: RuleMetadata) = apply { this.metadata = metadata }

    fun metadata(name: String, desc: String? = null) = apply { this.metadata = RuleMetadata(name, desc) }

    fun field(
        propertyName: String,
        name: String,
        configure: (ConditionFieldDef<T>.() -> ConditionFieldDef<T>)? = null
    ) = apply {
        val baseField = ConditionFieldDef<T>(
            propertyName = propertyName,
            name = name,
            value = value,
            constraints = listOf(FieldConstraint.Required)
        )
        field = configure?.invoke(baseField) ?: baseField
    }

    fun build(): ConditionRegistration<T> {
        return ConditionRegistration(
            conditionId = id,
            metadata = metadata,
            field = requireNotNull(field) { "Condition field is required: conditionId=$id" },
            conditionFactory = factory
        )
    }

    companion object {
        fun <T : Any> scalar(type: ConditionType<T>): ConditionBuilder<T> =
            ConditionBuilder(ConditionValue.Scalar(type))

        fun <T : Any> list(type: ConditionType<T>): ConditionBuilder<List<T>> =
            ConditionBuilder(ConditionValue.ListValue(type))
    }
}
