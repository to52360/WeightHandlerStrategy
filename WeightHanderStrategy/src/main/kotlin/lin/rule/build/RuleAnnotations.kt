package lin.rule.build

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class RuleField(
    val name: String = "",
    val description: String = "",
    val required: Boolean = true,
    val dataSource: String = "" // 配置下拉项数据源 (交给反射层来翻译)
)
