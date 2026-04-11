package lin.rule.build

import lin.bean.ComboCard
import lin.domain.WarInfo
import lin.rule.handler.IntentResult
import lin.rule.tree.RuleConfig
import kotlin.reflect.KClass

typealias RuleLogic = (callCard: ComboCard, warInfo: WarInfo) -> IntentResult

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

    // 存放通过 API 手动追加的扩展字段配置，全部改为延迟执行 (Lambda)
    private val extraFields = mutableListOf<() -> RuleFieldSpec>()

    fun id(id: String) = apply { this.id = id }
    fun factory(factory: RuleFactory<T>) = apply { this.factory = factory }
    fun metadata(metadata: RuleMetadata) = apply { this.metadata = metadata }
    fun metadata(name: String, desc: String? = null) = apply { this.metadata = RuleMetadata(name, desc) }

    // 核心 API：接收一个即时求值的 RuleFieldSpec 并包装为延迟提供者，保证内外逻辑统一
    fun extraField(spec: RuleFieldSpec) = apply {
        this.extraFields.add { spec }
    }

    // 新增延迟接收 API，专门接收闭包形式的构建块
    fun extraFieldLazy(specProvider: () -> RuleFieldSpec) = apply {
        this.extraFields.add(specProvider)
    }



    fun build(): RuleRegistration<T> {
        // 将外加的 extraFields 保存为不可变集合快照
        val currentExtraFields = extraFields.toList()
        return RuleRegistration(
            ruleId = id,
            metadata = metadata,
            parameterType = parameterType,
            ruleFactory = factory,
            // [完全延迟解析]：主属性的 parser 和额外扩展字段的 parser 统统只在调用该闭包时才会运算
            lazyFieldsResolver = {
                RuleFieldParser.parse(parameterType) + currentExtraFields.map { provider -> provider() }
            }
        )
    }
}






