package lin.rule.build

import lin.rule.context.RuleContext
import lin.rule.handler.RuleResult
import lin.rule.parse.RuleFieldParser
import lin.rule.parse.RuleFieldSpec
import lin.rule.tree.RuleConfig
import kotlin.reflect.KClass

typealias RuleLogic = RuleContext.() -> RuleResult

typealias RuleFactory<T> = (RuleConfig, T) -> RuleLogic

class RuleBuilder<T : Any>(
    private val parameterType: KClass<T>
) {
    private lateinit var id: String
    private lateinit var factory: RuleFactory<T>
    private var metadata: RuleMetadata? = null
        get() {
            if (field == null) field = RuleMetadata(id, id)
            return field!!
        }

    // 统一存放字段提供者：单条/批量都视为 () -> List<RuleFieldSpec>，build 时 flatMap 展开
    private val extraFields = mutableListOf<() -> List<RuleFieldSpec>>()

    fun id(id: String) = apply { this.id = id }
    fun factory(factory: RuleFactory<T>) = apply { this.factory = factory }
    fun metadata(metadata: RuleMetadata) = apply { this.metadata = metadata }
    fun metadata(name: String, desc: String? = null) = apply { this.metadata = RuleMetadata(name, desc) }

    fun extraField(spec: RuleFieldSpec) = apply {
        extraFields.add { listOf(spec) }
    }

    fun extraFieldLazy(specProvider: () -> RuleFieldSpec) = apply {
        extraFields.add { listOf(specProvider()) }
    }

    fun extraFieldsLazy(specsProvider: () -> List<RuleFieldSpec>) = apply {
        extraFields.add(specsProvider)
    }

    fun build(): RuleRegistration<T> {
        val snapshot = extraFields.toList()
        return RuleRegistration(
            ruleId = id,
            metadata = metadata,
            parameterType = parameterType,
            ruleFactory = factory,
            // [完全延迟解析]：所有字段的反射在前端拉取表单时才真正执行
            lazyFieldsResolver = {
                RuleFieldParser.parse(parameterType) + snapshot.flatMap { it() }
            }
        )
    }
}






