package lin.rule.tree

/**
 * 避免与 BindingGroupId / String 类型混淆，通过 value class 区分路由。
 */
@JvmInline
value class CardBindingId(val value: String)
