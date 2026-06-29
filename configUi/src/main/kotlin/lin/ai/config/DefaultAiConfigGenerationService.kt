package lin.ai.config

import lin.rule.condition.PipelineAssembler
import lin.ui.service.TreeConfigService
import lin.ui.tree_config.db.EvaluatorLeafSourceCatalog
import lin.ui.tree_config.validation.EvaluatorTreeValidator

/**
 * 面向 MCP 的配置生成门面。
 * 这里只承接作者侧流程；运行期真正解释配置的契约仍由 WeightHanderStrategy 的树模型决定。
 */
class DefaultAiConfigGenerationService(
    private val leafSourceCatalog: EvaluatorLeafSourceCatalog,
    private val treeConfigService: TreeConfigService,
    pipelineAssembler: PipelineAssembler
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
        val treeReport = validator.validate(request.config)
        return ValidationReport(
            ok = treeReport.ok,
            diagnostics = treeReport.diagnostics.map { d ->
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
            managerId = request.managerId,
            isTemplate = request.isTemplate
        )
        return SaveEvaluatorTreeResult(id = id, validation = validation)
    }
}
