package lin.rule.build

@Target(AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class ExtraField(
    val required: Boolean = true,
    val regex: String = "",
    val dataSource: String = "",
    val optionLabels: Array<String> = [],
    val optionValues: Array<String> = []
)
