package testRuleRegistration


import lin.rule.parse.RuleField


/**
 * RuleFieldParser 单元测试
 *
 * 覆盖范围：
 *  - parse()        ：无注解 / 有注解 / 可选字段 / dataSource / List 嵌套
 *  - resolveTypeStruct()：String / Int / Boolean / Double /
 *                        SelectType / ListType / List<SelectType> /
 *                        未知类型兜底
 */
class RuleFieldParserTest {

    // ─────────────────────────────────────────────
    // 辅助测试数据类
    // ─────────────────────────────────────────────

    /** 所有字段均无 @RuleField 注解 */
    data class NoAnnotationParams(
        val name: String,
        val count: Int
    )

    /** 字段带完整注解 */
    data class FullAnnotationParams(
        @RuleField(name = "用户名", description = "输入用户名", required = true)
        val username: String,

        @RuleField(name = "年龄", description = "输入年龄", required = false)
        val age: Int
    )

    /** dataSource 触发 SelectType */
    data class SelectParams(
        @RuleField(name = "分类", dataSource = "category_source")
        val category: String
    )

    /** List<String> → ListType(StringType) */
    data class ListStringParams(
        @RuleField(name = "标签列表")
        val tags: List<String>
    )

    /** List<Int> → ListType(IntType) */
    data class ListIntParams(
        val scores: List<Int>
    )

    /** List<String> + dataSource → ListType(SelectType) */
    data class MultiSelectParams(
        @RuleField(name = "多选分类", dataSource = "multi_source")
        val categories: List<String>
    )

    /** 包含 Boolean / Double 原子类型 */
    data class PrimitiveParams(
        val enabled: Boolean,
        val ratio: Double
    )

    /** 未知类型（自定义类）应兜底为 StringType */
    data class UnknownTypeHolder(val value: Any)

    data class CustomClass(val x: Int)
    data class UnknownFieldParams(
        val custom: CustomClass
    )

}
