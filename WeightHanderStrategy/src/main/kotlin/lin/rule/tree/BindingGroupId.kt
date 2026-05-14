package lin.rule.tree

/**
 * 封装 CardGroupBinding.id，用于 ConfigDispatcher 按 binding 组查找 CardWeightInfo。
 * 避免与 String 类型（cardId）混淆，通过 value class 区分路由。
 */
@JvmInline
value class BindingGroupId(val value: String)
