package lin.ai.config

import lin.repository.card_group.CardGroupService
import lin.repository.tree_config.EvaluatorLeafSourceCatalog
import lin.rule.condition.PipelineAssembler
import lin.rule.tree.EvaluatorLeafKind
import lin.rule.tree.EvaluatorTreeBindingType
import lin.ui.card_purpose.DefaultPurposeTagProvider
import lin.ui.card_purpose.PurposeTagProvider
import lin.ui.service.TreeConfigService
import lin.ui.tree_config.validation.EvaluatorTreeValidator

/**
 * 面向 MCP 的配置生成门面。
 * 这里只承接作者侧流程；运行期真正解释配置的契约仍由 WeightHanderStrategy 的树模型决定。
 * 仅负责评估树相关操作（叶子查询 / 校验 / 保存），卡池查询已拆到 CardGroupQueryService。
 */
class DefaultAiConfigGenerationService(
    private val leafSourceCatalog: EvaluatorLeafSourceCatalog,
    private val treeConfigService: TreeConfigService,
    pipelineAssembler: PipelineAssembler,
    private val cardGroupService: CardGroupService,
    private val purposeTagProvider: PurposeTagProvider = DefaultPurposeTagProvider()
) : AiConfigGenerationService {
    private val validator = EvaluatorTreeValidator(leafSourceCatalog, pipelineAssembler)

    override fun listCapabilityBackground(): AiCapabilityBackground {
        val all = leafSourceCatalog.loadAll()
        fun entryOf(item: lin.rule.tree.EvaluatorLeafMeta) = AiCapabilityEntry(
            sourceId = item.sourceId,
            name = item.name,
            desc = item.desc,
            requiredProperties = (item.builtInFields + item.fields).map { it.toAiFieldSpec() }
        )

        val orthogonalMetas = all.filter {
            it.kind is EvaluatorLeafKind.Condition.Orthogonal || it.kind is EvaluatorLeafKind.Rule.Orthogonal
        }
        val orthConditionId =
            orthogonalMetas.firstOrNull { it.kind is EvaluatorLeafKind.Condition.Orthogonal }?.sourceId
                ?: "orthogonal_condition"
        val orthRuleId = orthogonalMetas.firstOrNull { it.kind is EvaluatorLeafKind.Rule.Orthogonal }?.sourceId
            ?: "orthogonal_rule"
        return AiCapabilityBackground(
            codedRules = all.filter { it.kind is EvaluatorLeafKind.Rule.Coded }.map { entryOf(it) },
            plainConditions = all.filter { it.kind is EvaluatorLeafKind.Condition.Plain }.map { entryOf(it) },
            conditionTrees = all.filter { it.kind is EvaluatorLeafKind.Condition.Tree }.map { entryOf(it) },
            orthogonal = AiOrthogonalCapabilityPointer(
                conditionBuilderSourceId = orthConditionId,
                ruleBuilderSourceId = orthRuleId,
                note = "正交条件/规则的底层积木（DataSource / Transform / ConditionOperator / ScoreOperator 及其类型链路与参数）请调用 list_orthogonal_components 获取。背景规划阶段无需关注其类型细节，构造正交叶子时再查。"
            )
        )
    }

    override fun validateEvaluatorTree(request: SaveEvaluatorTreeRequest): ValidationReport {
        val treeReport = validator.validate(
            config = request.config,
            name = request.name,
            managerId = request.managerId,
            requireMetadata = true
        )
        val diagnostics = treeReport.diagnostics.toMutableList()

        // T-013: MCP-only 绑定类型感知校验
        when (request.config.bindingType) {
            EvaluatorTreeBindingType.PURPOSE_TAG -> {
                val availableTags = purposeTagProvider.tags().map { it.id.value }.toSet()
                request.config.bindingIds.forEach { tagId ->
                    if (tagId !in availableTags) {
                        diagnostics += EvaluatorTreeValidator.ValidationDiagnostic(
                            "binding_purpose_tag_not_found",
                            "绑定的用途标签不存在: $tagId。当前可用标签: $availableTags",
                            "bindingIds"
                        )
                    }
                }
            }

            EvaluatorTreeBindingType.GROUP -> {
                val enabledBindingIds = cardGroupService.loadAll(onlyEnabled = true)
                    .flatMap { it.bindings }
                    .map { it.id }
                    .toSet()
                request.config.bindingIds.forEach { bindingId ->
                    if (bindingId !in enabledBindingIds) {
                        diagnostics += EvaluatorTreeValidator.ValidationDiagnostic(
                            "binding_group_not_found",
                            "绑定的分组ID不存在或未启用: $bindingId",
                            "bindingIds"
                        )
                    }
                }
            }

            EvaluatorTreeBindingType.CARD -> {
                val enabledCardIds = cardGroupService.loadAll(onlyEnabled = true)
                    .flatMap { it.bindings }
                    .flatMap { it.cardIds }
                    .toSet()
                request.config.bindingIds.forEach { cardId ->
                    if (cardId !in enabledCardIds) {
                        diagnostics += EvaluatorTreeValidator.ValidationDiagnostic(
                            "binding_card_not_found",
                            "绑定的卡牌ID不存在于任何启用的卡池中: $cardId",
                            "bindingIds"
                        )
                    }
                }
            }
        }

        return ValidationReport(
            ok = diagnostics.isEmpty(),
            diagnostics = diagnostics.map { d ->
                ConfigDiagnostic(code = d.code, message = d.message, path = d.path)
            }
        )
    }

    override fun saveEvaluatorTree(request: SaveEvaluatorTreeRequest): SaveEvaluatorTreeResult {
        val validation = validateEvaluatorTree(request)
        if (!validation.ok) {
            return SaveEvaluatorTreeResult(id = request.existingId.orEmpty(), validation = validation)
        }

        val id = treeConfigService.saveConfig(
            name = request.name,
            config = request.config,
            description = request.description,
            existingId = request.existingId,
            enabled = request.enabled,
            managerId = request.managerId
        )
        return SaveEvaluatorTreeResult(id = id, validation = validation)
    }
}