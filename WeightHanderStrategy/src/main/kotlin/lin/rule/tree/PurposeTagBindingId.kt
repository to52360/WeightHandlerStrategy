package lin.rule.tree

/**
 * 封装用途标签 id，用于 ConfigDispatcher 按用途标签查找 CardWeightInfo。
 * 避免与 BindingGroupId / String 类型混淆，通过 value class 区分路由。
 */
@JvmInline
value class PurposeTagBindingId(val value: String)
