package lin.ui.condition_tree.validation

import lin.rule.condition.ConditionPayload
import lin.rule.condition.ConditionRegistry
import lin.rule.condition.ConditionTreeConfig
import lin.rule.condition.collectConditionRefs
import lin.rule.parse.SpecValidator

/**
 * 条件树参数完整性校验（内联创建树保存时校验，D-007 语义 B 补充）。
 *
 * 分界：内联创建（inlineCreated，消费方自动建的一次性树，实际使用，含全局光环）→ 保存时校验参数完整；
 * 工作台/MCP 建的模板（骨架，供评估树叶子覆盖参数参考）→ 参数不影响使用，不校验。
 * 校验点在 [ConditionTreeConfigService.saveConfig]，消费方（AuraBoost / 动态条件顺序）引用前树已保证
 * （内联创建树完整 / 骨架不保证由运行时 compileTree validate 兜底）。
 */
class ConditionTreeValidator(
    private val conditionRegistry: ConditionRegistry
) {
    /** 校验条件树参数完整性，返回错误消息列表（空 = 通过）。 */
    fun validateArgs(tree: ConditionTreeConfig): List<String> {
        val errors = mutableListOf<String>()
        tree.root.collectConditionRefs().distinctBy { it.refId }.forEach { ref ->
            when (ref) {
                is ConditionPayload.ConditionRef -> {
                    val registration = conditionRegistry.find(ref.conditionId)
                    if (registration == null) {
                        errors += "编码条件不存在: ${ref.conditionId}（refId=${ref.refId}）"
                    } else {
                        errors += SpecValidator.validate(ref.args, listOf(registration.field.toFieldSpec()))
                            .errors.map { "属性 ${it.propertyName}: ${it.message}" }
                    }
                }

                is ConditionPayload.PipelineRef -> {
                    val assembler = conditionRegistry.pipelineAssembler
                    if (assembler == null) {
                        errors += "管道装配器不可用（refId=${ref.refId}）"
                        return@forEach
                    }
                    for (call in ref.transforms) {
                        val transform = assembler.findTransform(call.transformId)
                        if (transform == null) {
                            errors += "管道步骤不存在: ${call.transformId}（refId=${ref.refId}）"
                        } else {
                            errors += SpecValidator.validate(call.args, transform.fields)
                                .errors.map { "属性 ${it.propertyName}: ${it.message}" }
                        }
                    }
                    val operator = assembler.findOperator(ref.operatorId)
                    if (operator == null) {
                        errors += "算子不存在: ${ref.operatorId}（refId=${ref.refId}）"
                    } else {
                        errors += SpecValidator.validate(ref.operatorArgs, operator.paramSpecs)
                            .errors.map { "属性 ${it.propertyName}: ${it.message}" }
                    }
                }
            }
        }
        return errors
    }
}
