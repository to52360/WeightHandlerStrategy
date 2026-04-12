package lin.rule.build

import kotlin.reflect.typeOf

/**
 * 提取的通用内联辅助方法：生成 RuleFieldSpec 并追加到 RuleBuilder 中
 * 使用 reified T 捕获真正的扩展字段类型。
 */
inline fun <reified V, T : Any> RuleBuilder<T>.extraField(
    propertyName: String,
    name: String,
    description: String = "",
    required: Boolean = true,
    dataSource: String? = null
): RuleBuilder<T> = apply {

    val constraints = mutableListOf<FieldConstraint>()
    if (required) {
        constraints.add(FieldConstraint.Required)
    }

    // 提早提取泛型 KType，但不要去碰复杂的反射递归(resolveTypeStruct)
    val kType = typeOf<V>()

    // 丢到闭包里，只有在前端拉取表单时才执行真正的反射结构推导
    this.extraFieldLazy {
        val typeStruct = RuleFieldParser.resolveTypeStruct(kType, dataSource)

        RuleFieldSpec(
            propertyName = propertyName,
            name = name,
            description = description,
            typeStruct = typeStruct,
            constraints = constraints
        )
    }
}

/**
 * 并行结构注入：将另一个 data class [C] 的所有字段并行追加进当前 RuleBuilder。
 *
 * 使用场景：规则参数需要 A + B 两个独立结构的字段，但不想定义 AB 合并类。
 * JSON 反序列化时各自只读自己关心的字段，多余字段会被忽略，互不干扰。
 *
 * 示例：
 * ```kotlin
 * builder
 *     .parseFields<FilterConfig>()
 *     .parseFields<SortConfig>()
 * ```
 *
 * 注意：反射推迟到前端拉取表单时才真正执行，注册阶段只捕获 KClass 引用。
 */
inline fun <reified C : Any, T : Any> RuleBuilder<T>.parseFields(): RuleBuilder<T> = apply {
    val kClass = C::class
    // 将整批字段的解析注册为一个惰性任务，真正执行时才触发反射
    this.extraFieldsLazy { RuleFieldParser.parse(kClass) }
}
