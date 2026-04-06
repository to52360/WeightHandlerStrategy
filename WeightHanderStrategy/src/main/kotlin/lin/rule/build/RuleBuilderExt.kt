package lin.rule.build

fun <T : Any> RuleBuilder<T>.requireField(
    propertyName: String,
    type: DynamicFieldType,
    required: Boolean = true,
    regex: String? = null,
    options: List<DynamicFieldOption> = emptyList()
): RuleBuilder<T> = apply {
    requireField(
        DynamicField(
            propertyName = propertyName,
            type = type,
            required = required,
            regex = regex,
            options = options
        )
    )
}

inline fun <reified F : Any, T : Any> RuleBuilder<T>.requireField(
    propertyName: String,
    required: Boolean = true,
    regex: String? = null,
    options: List<DynamicFieldOption> = emptyList()
): RuleBuilder<T> = apply {
    requireField(
        propertyName = propertyName,
        type = fieldTypeOf<F>(),
        required = required,
        regex = regex,
        options = options
    )
}

fun <T : Any> RuleBuilder<T>.requireIntField(
    propertyName: String,
    regex: String? = null,
    required: Boolean = true
): RuleBuilder<T> = apply {
    requireField(propertyName, DynamicFieldType.INT, required = required, regex = regex)
}

fun <T : Any> RuleBuilder<T>.requireBooleanField(
    propertyName: String,
    required: Boolean = true
): RuleBuilder<T> = apply {
    requireField(propertyName, DynamicFieldType.BOOLEAN, required = required)
}

fun <T : Any> RuleBuilder<T>.requireStringField(
    propertyName: String,
    regex: String? = null,
    required: Boolean = true
): RuleBuilder<T> = apply {
    requireField(propertyName, DynamicFieldType.STRING, required = required, regex = regex)
}

fun <T : Any> RuleBuilder<T>.requireStringSelectField(
    propertyName: String,
    options: List<String>,
    required: Boolean = true
): RuleBuilder<T> = apply {
    requireField(
        propertyName = propertyName,
        type = DynamicFieldType.STRING,
        required = required,
        options = options.map { DynamicFieldOption(label = it, value = it) }
    )
}

fun <T : Any> RuleBuilder<T>.requireIntSelectField(
    propertyName: String,
    options: List<Int>,
    required: Boolean = true
): RuleBuilder<T> = apply {
    requireField(
        propertyName = propertyName,
        type = DynamicFieldType.INT,
        required = required,
        options = options.map {
            val value = it.toString()
            DynamicFieldOption(label = value, value = value)
        }
    )
}

fun <T : Any> RuleBuilder<T>.requireBooleanSelectField(
    propertyName: String,
    options: List<Boolean>,
    required: Boolean = true
): RuleBuilder<T> = apply {
    requireField(
        propertyName = propertyName,
        type = DynamicFieldType.BOOLEAN,
        required = required,
        options = options.map {
            val value = it.toString()
            DynamicFieldOption(label = value, value = value)
        }
    )
}
