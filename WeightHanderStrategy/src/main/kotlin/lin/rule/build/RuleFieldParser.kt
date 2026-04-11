package lin.rule.build

import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.memberProperties

object RuleFieldParser {

    /**
     * 解析传入的配置数据类，生成对应的字段规范（AST AST）集合
     */
    fun <T : Any> parse(parameterType: KClass<T>): List<RuleFieldSpec> {
        val specs = mutableListOf<RuleFieldSpec>()

        // 遍历类的所有的成员属性
        // 注意：这里需要项目依赖 implementation("org.jetbrains.kotlin:kotlin-reflect")
        for (prop in parameterType.memberProperties) {
            val ruleFieldAnno = prop.findAnnotation<RuleField>()

            // 组装约束能力 (由于我们在前面解耦了，以后随时可以在这里追加新约束，比如检查是否有 @Pattern 注解等)
            val constraints = mutableListOf<FieldConstraint>()
            val isRequired = ruleFieldAnno?.required ?: true // 如果没打注解，默认我们也认为是必填。具体可以按你的业务调整
            if (isRequired) {
                constraints.add(FieldConstraint.Required)
            }

            // 当前属性名称
            val propName = prop.name
            // 优先使用注解中定义的展示名和描述，否则兜底用代码里的属性名
            val displayName = ruleFieldAnno?.name?.takeIf { it.isNotEmpty() } ?: propName
            val description = ruleFieldAnno?.description ?: ""
            val dataSource = ruleFieldAnno?.dataSource?.takeIf { it.isNotEmpty() } // 数据源 ID

            // 解析底层的 SICP 组合结构 (最核心的翻译层)
            val typeStruct = resolveTypeStruct(prop.returnType, dataSource)

            specs.add(
                RuleFieldSpec(
                    propertyName = propName,
                    name = displayName,
                    description = description,
                    typeStruct = typeStruct,
                    constraints = constraints
                )
            )
        }

        return specs
    }

    /**
     * 递归解析 Kotlin 类型 (KType)，并翻译为我们的 SICP FieldType
     */
    fun resolveTypeStruct(kType: KType, dataSource: String?): FieldType {
        val classifier = kType.classifier as? KClass<*> ?: return FieldType.StringType // 当无法识别时的兜底

        // 情景 1：判断是否是我们所谓的组合结构（Container）
        if (classifier.isSubclassOf(Collection::class)) {
            // 获取集合内部包裹的核心泛型元素，例如 List<String> 里面的那个 String
            val elementTypeOption = kType.arguments.firstOrNull()?.type
            val innerStruct = if (elementTypeOption != null) {
                // 【精髓】：集合包裹的东西可能是另一个结构，开启递归向下剥离
                // 多选下拉场景下，dataSourceId 是配置在外层的，因此我们继续往下传递给原子去吃掉它
                resolveTypeStruct(elementTypeOption, dataSource)
            } else {
                FieldType.StringType
            }
            // 包上一层包装
            return FieldType.ListType(innerStruct)
        }

        // 情景 2：剥离了所有的壳子，它是原子类型了 (Primitives)
        // 下拉框判断：只要是标明了具体数据源的，我们优先视为数据源依赖类型 (SelectType)
        if (!dataSource.isNullOrEmpty()) {
            return FieldType.SelectType(dataSource)
        }

        // 常规原生原子类型推导
        return when (classifier) {
            String::class -> FieldType.StringType
            Int::class -> FieldType.IntType
            Boolean::class -> FieldType.BooleanType
            Double::class -> FieldType.DoubleType
            else -> FieldType.StringType // 你可以增加更多的映射，或新增一个 UnknownType
        }
    }
}
