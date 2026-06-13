package lin.rule.registry

import lin.myLog
import lin.rule.build.RuleLogic
import lin.rule.build.RuleRegistration
import lin.rule.parse.FieldSpec
import lin.rule.parse.mapToRuleArgs
import lin.rule.tree.EVALUATOR_LEAF_BUILT_IN_FIELDS
import lin.rule.tree.EvaluatorLeafConfig
import lin.rule.tree.EvaluatorLeafMeta
import lin.rule.tree.EvaluatorLeafSourceType
import lin.serviceLoader.provider.RuleRegistrationProvider

// 提取为全局不变量，避免每次实例化 RuleMeta 时产生重复的对象分配与 GC 开销
val BUILT_IN_WEIGHT_PROPS: List<FieldSpec> = EVALUATOR_LEAF_BUILT_IN_FIELDS

// 配置元数据 DTO
data class RuleMeta(
    val ruleId: String,
    val name: String?,
    val desc: String?,
    // 树节点的自带 structure 属性，前端按照完全相同的强类型 AST 结构进行渲染
    // 并且他们是分配给 EvaluatorLeafConfig 根属性，而非 args 的
    val builtInWeightProps: List<FieldSpec> = BUILT_IN_WEIGHT_PROPS,
    // 泛型参数里解析出来的动态扩展验证属性 (归属于 EvaluatorLeafConfig.args)
    val fields: List<FieldSpec>
)

class RuleRegistry(
    providers: Collection<RuleRegistrationProvider>
) {
    private val registrationsById: Map<String, RuleRegistration<*>>

    init {
        val registrationMap = linkedMapOf<String, RuleRegistration<*>>()
        providers.forEach { provider ->
            provider.getRuleRegistrations().forEach { registration ->
                val previous = registrationMap.putIfAbsent(registration.ruleId, registration)
                require(previous == null) {
                    "Duplicate RuleRegistration ruleId=${registration.ruleId}, provider=${provider::class.java.name}"
                }
            }
        }
        registrationsById = registrationMap
        myLog.info { "loaded RuleRegistration ids=${registrationsById.keys}" }
    }

    fun all(): List<RuleRegistration<*>> = registrationsById.values.toList()

    fun find(ruleId: String): RuleRegistration<*>? = registrationsById[ruleId]

    fun require(ruleId: String): RuleRegistration<*> {
        return find(ruleId) ?: throw IllegalArgumentException("RuleRegistration not found: ruleId=$ruleId")
    }

    /**
     * 为 UI 或前端编排端提供的完整表单元数据列表
     * 这里调用时才会触发延迟反射解析，产出完整的 SICP 树模型
     */
    fun metadataList(): List<RuleMeta> {
        return registrationsById.values.map { reg ->
            RuleMeta(
                ruleId = reg.ruleId,
                name = reg.metadata?.name,
                desc = reg.metadata?.desc,
                // 这里发生了调用：触发实际的反射映射过程
                fields = reg.lazyFieldsResolver()
            )
        }
    }

    fun leafMetas(): List<EvaluatorLeafMeta> {
        return registrationsById.values.map { reg ->
            EvaluatorLeafMeta(
                sourceType = EvaluatorLeafSourceType.RULE,
                sourceId = reg.ruleId,
                name = reg.metadata?.name,
                desc = reg.metadata?.desc,
                fields = reg.lazyFieldsResolver()
            )
        }
    }

    /**
     * 根据前端下发的具体叶子配置（包含 sourceId + 运行参数 JSON map）
     * 实例化出真正的验证逻辑 (RuleLogic)
     */
    fun build(leafConfig: EvaluatorLeafConfig): RuleLogic {
        // 拿到外层壳 (Registration)
        val registration = require(leafConfig.sourceId)

        // 1. mapToRuleArgs: 将泛型字典 (Map<String, Any>) 映射为真实的强类型对象
        val params = mapToRuleArgs(leafConfig.args, registration.parameterType)

        @Suppress("UNCHECKED_CAST")
        val factory = registration.ruleFactory as (EvaluatorLeafConfig, Any) -> RuleLogic

        // 2. 直接调用工厂执行组装，生成闭包
        return factory(leafConfig, params)
    }
}
