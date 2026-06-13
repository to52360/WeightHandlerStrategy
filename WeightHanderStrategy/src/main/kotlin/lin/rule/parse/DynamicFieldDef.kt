package lin.rule.parse

/** 基础类型原子 */
sealed interface FieldType {
    object StringType : FieldType
    object IntType : FieldType
    object BooleanType : FieldType
    object DoubleType : FieldType

    // 下拉实际上就是一种自带数据源绑定的原子类型
    data class SelectType(val dataSourceId: String, val valueType: FieldType) : FieldType

    // 组合类型：对上面所有原子的包装
    data class ListType(val elementType: FieldType) : FieldType
}

/** 对数据结构的约束 */
sealed interface FieldConstraint {
    object Required : FieldConstraint

    // 数值范围约束
    data class IntRange(val min: Int, val max: Int) : FieldConstraint
    data class DoubleRange(val min: Double, val max: Double) : FieldConstraint

    // 正则匹配约束
    data class RegexPattern(val pattern: String) : FieldConstraint
}

/** 最终承载每个配置属性的完整模型 */
data class FieldSpec(
    val propertyName: String,
    val name: String,
    val description: String,
    val typeStruct: FieldType, // 这里是 SICP 结构的体现
    val constraints: List<FieldConstraint> = emptyList()
)

/**
 * 为字段添加 refId 前缀，用于条件树作为评估树叶子时避免同名字段冲突。
 * propertyName 变为 "refId.propertyName"，name 附加条件显示名。
 */
fun FieldSpec.withConditionPrefix(refId: String, conditionDisplayName: String): FieldSpec {
    return copy(
        propertyName = "$refId.$propertyName",
        name = "$name ($conditionDisplayName)"
    )
}

/**
 * 从带 refId 前缀的 args 中提取属于指定 refId 的参数，并去掉前缀。
 * 例如 args = {"foo.maxCost": 5, "bar.threshold": 3}，refId="foo" → {"maxCost": 5}
 */
fun Map<String, Any>.extractPrefixedArgs(refId: String): Map<String, Any> {
    val prefix = "$refId."
    return filterKeys { it.startsWith(prefix) }
        .mapKeys { it.key.removePrefix(prefix) }
}
