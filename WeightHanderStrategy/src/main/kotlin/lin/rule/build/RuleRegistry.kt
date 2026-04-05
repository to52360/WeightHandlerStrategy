package lin.rule.build

import lin.myLog
import lin.rule.tree.RuleConfig
import lin.utils.serviceLoader.ServiceLoaderUtils

data class RuleUiItem(
    val ruleId: String,
    val name: String,
    val desc: String?,
    val dynamicFields: List<DynamicField> = emptyList()
)

class RuleRegistry(
    providers: Collection<RuleRegistrationProvider> = ServiceLoaderUtils.getCacheServices(RuleRegistrationProvider::class.java)
) {
    private val registrationsById: Map<String, RuleRegistration>

    init {
        val registrationMap = linkedMapOf<String, RuleRegistration>()
        providers.forEach { provider ->
            provider.getRuleRegistrations().forEach { registration ->
                val previous = registrationMap.putIfAbsent(registration.ruleId, registration)
                require(previous == null) {
                    "Duplicate RuleRegistration ruleId=${registration.ruleId}, provider=${provider::class.java.name}"
                }
            }
        }
        registrationsById = registrationMap
        myLog.info { "loaded RuleRegistration ids=${registrationsById.keys}" }
    }

    fun all(): List<RuleRegistration> = registrationsById.values.toList()

    fun find(ruleId: String): RuleRegistration? = registrationsById[ruleId]

    fun require(ruleId: String): RuleRegistration {
        return find(ruleId) ?: throw IllegalArgumentException("RuleRegistration not found: ruleId=$ruleId")
    }

    fun uiItems(): List<RuleUiItem> {
        return registrationsById.values.map { registration ->
            val metadata = registration.metadata
            RuleUiItem(
                ruleId = registration.ruleId,
                name = metadata?.name ?: registration.ruleId,
                desc = metadata?.desc,
                dynamicFields = metadata?.dynamicFields.orEmpty()
            )
        }
    }

    fun build(ruleId: String, ruleConfig: RuleConfig): RuleLogic {
        val registration = require(ruleId)

        // --- 校验动态参数 ---
        registration.metadata?.dynamicFields?.forEach { field ->
            val value = ruleConfig.args[field.propertyName]
            requireNotNull(value) {
                "Rule(ruleId='$ruleId') 构建错误: 缺少必填的动态参数 '${field.propertyName}'"
            }

            // 兼容性类型校验 (因为来自于 Json，可能是 Number/String)
            when (field.type) {
                Int::class.java -> {
                    val isValid = value is Number || (value is String && value.toIntOrNull() != null)
                    require(isValid) { "参数 '${field.propertyName}' 的值('$value') 必须可以转换为 Int 类型" }
                }

                Boolean::class.java -> {
                    val isValid = value is Boolean || (value is String && value.toBooleanStrictOrNull() != null)
                    require(isValid) { "参数 '${field.propertyName}' 的值('$value') 必须可以转换为 Boolean 类型" }
                }
            }

            // 正则校验
            if (!field.regex.isNullOrEmpty()) {
                val strValue = value.toString()
                require(strValue.matches(Regex(field.regex))) {
                    "参数 '${field.propertyName}' 的值('$strValue') 校验失败，不符合正则: ${field.regex}"
                }
            }
        }

        return registration.spec.ruleFactory(ruleConfig)
    }

    fun build(ruleConfig: RuleConfig): RuleLogic {
        return build(ruleConfig.ruleId, ruleConfig)
    }
}
