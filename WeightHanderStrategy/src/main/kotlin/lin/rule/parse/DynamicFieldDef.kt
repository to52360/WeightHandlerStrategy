package lin.rule.parse

/** 基础类型原子 */
sealed interface FieldType {
    object StringType : FieldType
    object IntType : FieldType
    object BooleanType : FieldType
    object DoubleType : FieldType

    // 下拉实际上就是一种自带数据源绑定的原子类型
    data class SelectType(val dataSourceId: String) : FieldType

    // 组合类型：对上面所有原子的包装
    data class ListType(val elementType: FieldType) : FieldType
}

/** 对数据结构的约束 */
sealed interface FieldConstraint {
    object Required : FieldConstraint
}

/** 最终承载每个配置属性的完整模型 */
data class RuleFieldSpec(
    val propertyName: String,
    val name: String,
    val description: String,
    val typeStruct: FieldType, // 这里是 SICP 结构的体现
    val constraints: List<FieldConstraint>
)
