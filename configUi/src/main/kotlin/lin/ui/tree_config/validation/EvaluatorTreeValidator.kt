package lin.ui.tree_config.validation

import lin.rule.condition.ConditionPayload
import lin.rule.condition.PipelineAssembler
import lin.rule.parse.SpecValidator
import lin.rule.score.ScoreEffect
import lin.rule.tree.*
import lin.ui.tree_config.bridge.leafKind

import lin.ui.tree_config.db.EvaluatorLeafSourceCatalog

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
    private val pipelineAssembler: PipelineAssembler
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

        // PRUNE 时 missValue 无意义（warn 级，不拦截）
        if (leafConfig.guardMissBehavior == GuardMissBehavior.PRUNE && scoreable.scoreEffect.missValue != 0.0) {
            diagnostics += ValidationDiagnostic(
                code = "prune_miss_value_warn",
                message = "剪枝(PRUNE)行为下 missValue 不会被使用，当前值 ${scoreable.scoreEffect.missValue} 无意义",
                path = "$prefix.guardMissBehavior"
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
