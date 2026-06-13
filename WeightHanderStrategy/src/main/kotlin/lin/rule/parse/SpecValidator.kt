package lin.rule.parse

data class ValidationError(
    val propertyName: String,
    val errorCode: String,
    val message: String
)

data class ValidationResult(
    val errors: List<ValidationError>
) {
    val isValid: Boolean get() = errors.isEmpty()
}

object SpecValidator {

    /**
     * 校验传入的参数字典是否满足 FieldSpec 元数据契约限制
     */
    fun validate(args: Map<String, Any?>, specs: List<FieldSpec>): ValidationResult {
        val errors = mutableListOf<ValidationError>()

        for (spec in specs) {
            val propertyName = spec.propertyName
            val value = args[propertyName]

            // 1. 必填校验
            val hasRequired = spec.constraints.contains(FieldConstraint.Required)
            if (hasRequired && (value == null || (value is String && value.isBlank()))) {
                errors.add(
                    ValidationError(
                        propertyName = propertyName,
                        errorCode = "MISSING_REQUIRED",
                        message = "必填字段 [${propertyName}] 缺失或为空"
                    )
                )
                continue // 缺失必填字段后，不继续执行其它约束校验
            }

            // 如果值不为 null，执行类型和约束校验
            if (value != null) {
                // 2. 类型契约匹配性校验
                val typeErrors = validateType(propertyName, value, spec.typeStruct)
                if (typeErrors.isNotEmpty()) {
                    errors.addAll(typeErrors)
                    continue // 类型都不匹配，则无法进行后续的值约束校验
                }

                // 3. 值属性约束校验
                for (constraint in spec.constraints) {
                    when (constraint) {
                        is FieldConstraint.Required -> {
                            // 已经在前面校验过了
                        }

                        is FieldConstraint.IntRange -> {
                            val num = (value as? Number)?.toInt()
                            if (num == null || num < constraint.min || num > constraint.max) {
                                errors.add(
                                    ValidationError(
                                        propertyName = propertyName,
                                        errorCode = "VALUE_OUT_OF_BOUNDS",
                                        message = "字段 [${propertyName}] 的值 [${value}] 越界，要求范围为 [${constraint.min}, ${constraint.max}]"
                                    )
                                )
                            }
                        }

                        is FieldConstraint.DoubleRange -> {
                            val num = (value as? Number)?.toDouble()
                            if (num == null || num < constraint.min || num > constraint.max) {
                                errors.add(
                                    ValidationError(
                                        propertyName = propertyName,
                                        errorCode = "VALUE_OUT_OF_BOUNDS",
                                        message = "字段 [${propertyName}] 的值 [${value}] 越界，要求范围为 [${constraint.min}, ${constraint.max}]"
                                    )
                                )
                            }
                        }

                        is FieldConstraint.RegexPattern -> {
                            val str = value.toString()
                            if (!Regex(constraint.pattern).matches(str)) {
                                errors.add(
                                    ValidationError(
                                        propertyName = propertyName,
                                        errorCode = "PATTERN_MISMATCH",
                                        message = "字段 [${propertyName}] 的值 [${value}] 不匹配正则表达式 [${constraint.pattern}]"
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        return ValidationResult(errors)
    }

    /**
     * 校验值是否符合 FieldType 结构声明
     */
    private fun validateType(propertyName: String, value: Any, type: FieldType): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        when (type) {
            FieldType.StringType -> {
                // 任何类型都可以通过 toString() 转换为 String，但在契约上，我们期望传递的是真正的 String 类型
                if (value !is String) {
                    errors.add(createTypeMismatchError(propertyName, "String", value))
                }
            }

            FieldType.IntType -> {
                if (value !is Int && value !is Long && value !is Short && value !is Byte) {
                    // 如果是 Double/Float 且实际上是整数，也可以容忍
                    if (value is Number) {
                        val dVal = value.toDouble()
                        if (dVal % 1.0 != 0.0) {
                            errors.add(createTypeMismatchError(propertyName, "Int", value))
                        }
                    } else {
                        errors.add(createTypeMismatchError(propertyName, "Int", value))
                    }
                }
            }

            FieldType.DoubleType -> {
                if (value !is Number) {
                    errors.add(createTypeMismatchError(propertyName, "Double", value))
                }
            }

            FieldType.BooleanType -> {
                if (value !is Boolean) {
                    errors.add(createTypeMismatchError(propertyName, "Boolean", value))
                }
            }

            is FieldType.SelectType -> {
                // Select 属于原子类的带绑定版本，校验其底层 valueType 类型即可
                errors.addAll(validateType(propertyName, value, type.valueType))
            }

            is FieldType.ListType -> {
                if (value !is List<*>) {
                    errors.add(createTypeMismatchError(propertyName, "List", value))
                } else {
                    // 递归校验列表里的每一项
                    for (item in value) {
                        if (item == null) {
                            errors.add(
                                ValidationError(
                                    propertyName = propertyName,
                                    errorCode = "NULL_LIST_ELEMENT",
                                    message = "字段 [${propertyName}] 的列表元素不允许为 null"
                                )
                            )
                        } else {
                            val itemErrors = validateType(propertyName, item, type.elementType)
                            if (itemErrors.isNotEmpty()) {
                                errors.add(
                                    ValidationError(
                                        propertyName = propertyName,
                                        errorCode = "TYPE_MISMATCH",
                                        message = "字段 [${propertyName}] 的列表嵌套项类型不匹配，元素 [${item}] 不符合 [${type.elementType::class.simpleName}]"
                                    )
                                )
                                break // 嵌套项只报一次错误，避免重复刷屏
                            }
                        }
                    }
                }
            }
        }
        return errors
    }

    private fun createTypeMismatchError(propertyName: String, expectedType: String, actualValue: Any): ValidationError {
        return ValidationError(
            propertyName = propertyName,
            errorCode = "TYPE_MISMATCH",
            message = "字段 [${propertyName}] 的类型应为 [${expectedType}]，但实际为 [${actualValue::class.simpleName}]"
        )
    }
}
