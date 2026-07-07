package lin.ai.config

import lin.rule.condition.PipelineAssembler
import lin.rule.tree.EvaluatorTreeBindingType
import lin.ui.card_group.db.CardGroupService
import lin.ui.service.TreeConfigService
import lin.ui.tree_config.db.EvaluatorLeafSourceCatalog
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
    private val cardGroupService: CardGroupService
) : AiConfigGenerationService {
    private val validator = EvaluatorTreeValidator(leafSourceCatalog, pipelineAssembler)

    override fun listEvaluatorLeafKinds(): List<AiEvaluatorLeafKind> {
        return leafSourceCatalog.loadAll().map { item ->
            AiEvaluatorLeafKind(
                kind = item.kind,
                sourceId = item.sourceId,
                name = item.name,
                desc = item.desc,
                fields = (item.builtInFields + item.fields).map { it.toAiFieldSpec() }
            )
        }
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
                diagnostics += EvaluatorTreeValidator.ValidationDiagnostic(
                    "binding_purpose_tag_unavailable",
                    "用途标签(PURPOSE_TAG)绑定类型 V1 暂不可用",
                    "bindingType"
                )
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