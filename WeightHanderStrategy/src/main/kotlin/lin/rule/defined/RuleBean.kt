package lin.rule.defined

import lin.rule.RuleLevel

// 注册入口：聚合规则 identity、构建规格和 UI 元信息。
data class RuleRegistration(
    val ruleId: String,
    val spec: RuleSpec,
    val metadata: RuleMetadata?
)

// UI 展示信息：仅承载展示名和描述，不参与规则标识。
data class RuleMetadata(
    val name: String?,
    val desc: String?
)

// 规则构建规格：仅描述规则等级和构建逻辑，不包含 identity。
data class RuleSpec(
    val ruleLevel: RuleLevel,
    val ruleFactory: RuleFactory
)

