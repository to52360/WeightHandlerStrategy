package lin.ui.tree_config.validation

import lin.repository.condition_tree.ConditionTreeConfigService
import lin.repository.tree_config.EvaluatorLeafSourceCatalog
import lin.rule.condition.ConditionPayload
import lin.rule.condition.PipelineAssembler
import lin.rule.parse.SpecValidator
import lin.rule.score.ScoreEffect
import lin.rule.tree.*
import lin.ui.tree_config.bridge.leafKind

/**
 * 评估树配置的统一验证器，同时服务 UI 手动保存和 AI/MCP 自动保存两条路径。
 *
 * 验证内容：
 * - root 引用的 leaf nodeId 是否缺失 leafConfig
 * - leafConfig 的 kind + sourceId 是否在 leafSourceCatalog 中存在
 * - leafConfig.args 是否满足 FieldSpec 类型/必填/约束契约
 * - Guard+ScoreEffect 相容性：Kind 匹配、PipelineRef/SourceScore 引用非空。
 *   PipelineRef 注册表级校验委托给 PipelineAssembler.validatePipelineRef()。
 */
class EvaluatorTreeValidator(
    private val leafSourceCatalog: EvaluatorLeafSourceCatalog,
    private val pipelineAssembler: PipelineAssembler,
    private val conditionTreeService: ConditionTreeConfigService? = null
) {
    data class ValidationDiagnostic(
        val code: String,
        val message: String,
        val path: String? = null
    )

    data class ValidationReport(
        val ok: Boolean,
        val diagnostics: List<ValidationDiagnostic> = emptyList()
    )

    fun validate(
        config: EvaluatorTreeConfig,
        name: String? = null,
        managerId: String? = null,
        requireMetadata: Boolean = false
    ): ValidationReport {
        val diagnostics = mutableListOf<ValidationDiagnostic>()
        val leafConfigs = config.leafConfigs
        val knownSources = leafSourceCatalog.loadAll().associateBy { it.kind to it.sourceId }

        // 0. 元数据校验（名称、卡组管理ID、绑定目标）
        if (name != null && name.trim().isBlank()) {
            diagnostics += ValidationDiagnostic("config_name_blank", "配置名称不能为空", "name")
        }
        if (requireMetadata) {
            if (config.bindingType == EvaluatorTreeBindingType.GROUP && managerId.isNullOrBlank()) {
                diagnostics += ValidationDiagnostic(
                    "manager_id_missing",
                    "卡组专属策略必须指定所属卡组管理 ID (managerId)",
                    "managerId"
                )
            }
            if (config.bindingType == EvaluatorTreeBindingType.CARD && config.bindingIds.any { it.trim().isBlank() }) {
                diagnostics += ValidationDiagnostic(
                    "card_id_blank",
                    "卡牌 ID 不能为空",
                    "bindingIds"
                )
            }
            if (config.bindingIds.isEmpty()) {
                diagnostics += ValidationDiagnostic(
                    "binding_ids_empty",
                    "必须选择至少一个绑定目标 (bindingIds)",
                    "bindingIds"
                )
            }
        }

        // 1. root 引用的 leaf nodeId 是否缺失 leafConfig
        collectReferencedLeafNodeIds(config.root).forEach { nodeId ->
            if (leafConfigs[nodeId] == null) {
                diagnostics += ValidationDiagnostic(
                    code = "missing_leaf_config",
                    message = "评估树节点缺少 leafConfig: nodeId=$nodeId",
                    path = "leafConfigs.$nodeId"
                )
            }
        }

        // 2. 每个 leafConfig 的合法性校验
        leafConfigs.values.forEach { leafConfig ->
            validateLeafConfigInternal(leafConfig, knownSources, diagnostics)
        }

        return ValidationReport(
            ok = diagnostics.isEmpty(),
            diagnostics = diagnostics
        )
    }

    /**
     * 单节点严格验证，供草稿池增量填装叶子时快速 Fail-Fast 使用。
     */
    fun validateSingleLeafConfig(leafConfig: EvaluatorLeafConfig): ValidationReport {
        val diagnostics = mutableListOf<ValidationDiagnostic>()
        val knownSources = leafSourceCatalog.loadAll().associateBy { it.kind to it.sourceId }
        validateLeafConfigInternal(leafConfig, knownSources, diagnostics)
        return ValidationReport(ok = diagnostics.isEmpty(), diagnostics = diagnostics)
    }

    private fun validateLeafConfigInternal(
        leafConfig: EvaluatorLeafConfig,
        knownSources: Map<Pair<EvaluatorLeafKind, String>, EvaluatorLeafMeta>,
        diagnostics: MutableList<ValidationDiagnostic>
    ) {
        val meta = knownSources[leafConfig.leafKind to leafConfig.sourceId]
        if (meta == null) {
            // condition-tree-tooling/Q-001（2026-08-09 定论）：评估树 CONDITION_TREE 叶子禁止引用
            // 内联创建（inlineCreated=true）的条件树——内联树生命周期绑定消费方（消费方删除→树悬空）。
            // 内联树被 leafSourceCatalog（filterNot inlineCreated）排除在可引用来源外，会先走到本分支，
            // 这里优先给出明确错误码，避免 AI/用户困惑"树明明存在却报未知来源"。
            if (leafConfig is ConditionTreeLeafConfig && conditionTreeService != null) {
                val treeMeta = conditionTreeService.loadAllMeta().firstOrNull { it.id == leafConfig.sourceId }
                if (treeMeta != null && treeMeta.inlineCreated) {
                    diagnostics += ValidationDiagnostic(
                        code = "condition_tree_inline_reference_forbidden",
                        message = "评估树不能引用内联创建的条件树 [${treeMeta.name}]（id=${leafConfig.sourceId}）：" +
                                "内联树生命周期绑定消费方，评估树引用会导致悬空。请改用 save_condition_tree 创建可复用模板树后引用。",
                        path = "leafConfigs.${leafConfig.nodeId}.sourceId"
                    )
                    return
                }
            }
            diagnostics += ValidationDiagnostic(
                code = "unknown_leaf_source",
                message = "未知评估树叶子来源: kind=${leafConfig.leafKind}, sourceId=${leafConfig.sourceId}",
                path = "leafConfigs.${leafConfig.nodeId}.sourceId"
            )
            return
        }

        val allFields = meta.builtInFields + meta.fields
        val validationResult = SpecValidator.validate(leafConfig.args, allFields)
        if (!validationResult.isValid) {
            validationResult.errors.forEach { error ->
                diagnostics += ValidationDiagnostic(
                    code = error.errorCode,
                    message = error.message,
                    path = "leafConfigs.${leafConfig.nodeId}.args.${error.propertyName}"
                )
            }
        }

        // T-009: Guard+ScoreEffect 相容性校验
        validateScoreEffectCompatibility(leafConfig, diagnostics)
    }

    // ================================================================
    // T-009: Guard + ScoreEffect 策略校验
    // ================================================================

    /**
     * ScoreEffect 相容性校验。
     * Condition 类叶子由类型系统编译期保证只能用 ConstantScore，无需运行时校验。
     * Rule 类叶子若用 SourceScore，校验数据源/算子非空。
     */
    private fun validateScoreEffectCompatibility(
        leafConfig: EvaluatorLeafConfig,
        diagnostics: MutableList<ValidationDiagnostic>
    ) {
        val prefix = "leafConfigs.${leafConfig.nodeId}"
        val scoreable = leafConfig as? Scoreable ?: return

        when (leafConfig) {
            is OrthogonalConditionLeafConfig -> {
                // Condition.Orthogonal: scoreEffect 类型已由编译期保证为 ConstantScore
                // 仅校验 PipelineRef 非空
                validatePipelineRef(leafConfig.guardCondition, prefix, diagnostics)
            }

            is ConditionLeafConfig, is ConditionTreeLeafConfig -> {
                // Condition.Plain / Condition.Tree: scoreEffect 类型已由编译期保证为 ConstantScore
            }

            is EvaluatorLeafConfig.Rule -> {
                // Rule: 若用 SourceScore，校验数据源/算子非空
                val effect = scoreable.scoreEffect
                if (effect is ScoreEffect.SourceScore) {
                    validateSourceScore(effect, prefix, diagnostics)
                }
            }
        }

        // PRUNE/BAN 时 missValue 无意义（warn 级，不拦截）
        if (leafConfig.guardMissBehavior != GuardMissBehavior.SCORE
            && scoreable.scoreEffect.missValue != 0.0
        ) {
            val behaviorName = when (leafConfig.guardMissBehavior) {
                GuardMissBehavior.PRUNE -> "门控短路(PRUNE)"
                GuardMissBehavior.BAN -> "禁止(BAN)"
                GuardMissBehavior.SCORE -> "给兜底分(SCORE)"
            }
            diagnostics += ValidationDiagnostic(
                code = "non_score_miss_value_warn",
                message = "$behaviorName 行为下 missValue 不会被使用，当前值 ${scoreable.scoreEffect.missValue} 无意义",
                path = "$prefix.guardMissBehavior"
            )
        }

        // Q-037 常驻树分隐患（play-value-model 阶段二）：SCORE 行为下 missValue > 0 = 常驻分——
        // 守卫未命中也拿正分 → ts 恒正 → 惜售 N 门槛（ts≠0 / ts>0 放行判定）永远成立，配了 N 也白配（惜售被架空）。
        // 提示意图：表达「未命中也有兜底」应保持 missValue=0，由命中分支给分；误配在此拦截并回显。
        if (leafConfig.guardMissBehavior == GuardMissBehavior.SCORE && scoreable.scoreEffect.missValue > 0.0) {
            diagnostics += ValidationDiagnostic(
                code = "resident_score_warn",
                message = "常驻分警告：守卫未命中（SCORE 兜底）时 missValue=${scoreable.scoreEffect.missValue} > 0" +
                        " → 该卡条件未命中仍拿正分（ts 恒正）→ 惜售门槛 N 的放行判定被架空（配了 N 也白配，门控永远放行）。" +
                        "请核对是否有意为之；通常应保持 missValue=0，由命中分支给分。",
                path = "$prefix.scoreEffect.missValue"
            )
        }
    }

    private fun validatePipelineRef(
        ref: ConditionPayload.PipelineRef,
        prefix: String,
        diagnostics: MutableList<ValidationDiagnostic>
    ) {
        if (ref.sourceId.isBlank()) {
            diagnostics += ValidationDiagnostic(
                code = "pipeline_missing_source",
                message = "正交管道缺少数据源(dataSource)",
                path = "$prefix.guardCondition.sourceId"
            )
        }
        if (ref.operatorId.isBlank()) {
            diagnostics += ValidationDiagnostic(
                code = "pipeline_missing_operator",
                message = "正交管道缺少算子(operator)",
                path = "$prefix.guardCondition.operatorId"
            )
        }

        // 委托 PipelineAssembler 做注册表级校验（存在性、类型链兼容、参数）
        if (ref.sourceId.isNotBlank() && ref.operatorId.isNotBlank()) {
            val pipelineValidation = pipelineAssembler.validatePipelineRef(ref)
            pipelineValidation.errors.forEach { error ->
                diagnostics += ValidationDiagnostic(
                    code = error.errorCode,
                    message = error.message,
                    path = "$prefix.guardCondition"
                )
            }
        }
    }

    private fun validateSourceScore(
        effect: ScoreEffect.SourceScore,
        prefix: String,
        diagnostics: MutableList<ValidationDiagnostic>
    ) {
        if (effect.sourceId.isBlank()) {
            diagnostics += ValidationDiagnostic(
                code = "source_score_missing_source",
                message = "数据源评分缺少数据源(sourceId)",
                path = "$prefix.scoreEffect.sourceId"
            )
        }
        if (effect.operatorId.isBlank()) {
            diagnostics += ValidationDiagnostic(
                code = "source_score_missing_operator",
                message = "数据源评分缺少算子(operatorId)",
                path = "$prefix.scoreEffect.operatorId"
            )
        }
    }

    fun collectReferencedLeafNodeIds(root: EvaluatorNode): Set<String> {
        val ids = linkedSetOf<String>()

        fun visit(node: EvaluatorNode) {
            when (node) {
                is LogicNode.And -> node.children.forEach(::visit)
                is LogicNode.Branch -> {
                    val payload = node.payload
                    if (payload is EvaluatorPayload.BranchCondition) {
                        ids += payload.nodeId
                    }
                    visit(node.onTrue)
                    visit(node.onFalse)
                }

                is LogicNode.Leaf -> {
                    val payload = node.payload
                    if (payload is EvaluatorPayload.Rule) {
                        ids += payload.nodeId
                    }
                }

                is LogicNode.Not -> visit(node.child)
                is LogicNode.Or -> node.children.forEach(::visit)
            }
        }

        visit(root)
        return ids
    }
}
