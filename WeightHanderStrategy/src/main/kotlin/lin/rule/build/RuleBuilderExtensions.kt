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
