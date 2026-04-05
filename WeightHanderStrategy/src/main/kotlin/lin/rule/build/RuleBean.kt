package lin.rule.build

// 注册入口：聚合规则 identity、构建规格和 UI 元信息。
data class RuleRegistration(
    val ruleId: String,
    val spec: RuleSpec,
    val metadata: RuleMetadata?
)

// UI 展示信息：仅承载展示名和描述，不参与规则标识。
data class RuleMetadata(
    val name: String?,
    val desc: String?,
    val dynamicFields: List<DynamicField> = emptyList()
)

// 动态字段定义：用于 UI 页面等动态配置内容
data class DynamicField(
    val propertyName: String,
    val type: Class<*>,
    val regex: String? = null
)

// 规则构建规格：仅描述规则等级和构建逻辑，不包含 identity。
data class RuleSpec(
    val ruleFactory: RuleFactory
)
