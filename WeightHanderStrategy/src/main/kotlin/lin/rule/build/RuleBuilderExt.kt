package lin.rule.build

fun <T : Any> RuleBuilder<T>.extraField(
    propertyName: String,
    type: DynamicFieldType,
    required: Boolean = true,
    regex: String? = null,
    options: List<DynamicFieldOption> = emptyList(),
    dataSource: String? = null
): RuleBuilder<T> = apply {
    extraField(
        DynamicField(
            propertyName = propertyName,
            type = type,
            required = required,
            regex = regex,
            options = options,
            dataSource = dataSource
        )
    )
}

inline fun <reified F : Any, T : Any> RuleBuilder<T>.extraField(
    propertyName: String,
    required: Boolean = true,
    regex: String? = null,
    options: List<DynamicFieldOption> = emptyList(),
    dataSource: String? = null
): RuleBuilder<T> = apply {
    extraField(
        propertyName = propertyName,
        type = fieldTypeOf<F>(),
        required = required,
        regex = regex,
        options = options,
        dataSource = dataSource
    )
}

fun <T : Any> RuleBuilder<T>.extraIntField(
    propertyName: String,
    regex: String? = null,
    required: Boolean = true
): RuleBuilder<T> = apply {
    extraField(propertyName, DynamicFieldType.INT, required = required, regex = regex)
}

fun <T : Any> RuleBuilder<T>.extraBooleanField(
    propertyName: String,
    required: Boolean = true
): RuleBuilder<T> = apply {
    extraField(propertyName, DynamicFieldType.BOOLEAN, required = required)
}

fun <T : Any> RuleBuilder<T>.extraStringField(
    propertyName: String,
    regex: String? = null,
    required: Boolean = true
): RuleBuilder<T> = apply {
    extraField(propertyName, DynamicFieldType.STRING, required = required, regex = regex)
}

fun <T : Any> RuleBuilder<T>.extraStringSelectField(
    propertyName: String,
    options: List<String>,
    required: Boolean = true
): RuleBuilder<T> = apply {
    extraField(
        propertyName = propertyName,
        type = DynamicFieldType.STRING,
        required = required,
        options = options.map { DynamicFieldOption(label = it, value = it) }
    )
}

fun <T : Any> RuleBuilder<T>.extraIntSelectField(
    propertyName: String,
    options: List<Int>,
    required: Boolean = true
): RuleBuilder<T> = apply {
    extraField(
        propertyName = propertyName,
        type = DynamicFieldType.INT,
        required = required,
        options = options.map {
            val value = it.toString()
            DynamicFieldOption(label = value, value = value)
        }
    )
}

fun <T : Any> RuleBuilder<T>.extraBooleanSelectField(
    propertyName: String,
    options: List<Boolean>,
    required: Boolean = true
): RuleBuilder<T> = apply {
    extraField(
        propertyName = propertyName,
        type = DynamicFieldType.BOOLEAN,
        required = required,
        options = options.map {
            val value = it.toString()
            DynamicFieldOption(label = value, value = value)
        }
    )
}

fun <T : Any> RuleBuilder<T>.extraRegisteredSelectField(
    propertyName: String,
    dataSource: String,
    type: DynamicFieldType = DynamicFieldType.STRING,
    required: Boolean = true
): RuleBuilder<T> = apply {
    extraField(
        propertyName = propertyName,
        type = type,
        required = required,
        dataSource = dataSource
    )
}

