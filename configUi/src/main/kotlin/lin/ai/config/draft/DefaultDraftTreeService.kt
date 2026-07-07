package lin.ai.config.draft

import lin.ai.config.*
import lin.rule.condition.PipelineAssembler
import lin.rule.tree.EvaluatorLeafConfig
import lin.rule.tree.EvaluatorTreeConfig
import lin.ui.tree_config.db.EvaluatorLeafSourceCatalog
import lin.ui.tree_config.validation.EvaluatorTreeValidator
import lin.utils.nextShortId
import java.util.concurrent.ConcurrentHashMap

class DefaultDraftTreeService(
    leafSourceCatalog: EvaluatorLeafSourceCatalog,
    pipelineAssembler: PipelineAssembler,
    private val aiConfigGenerationService: AiConfigGenerationService
) : DraftTreeService {

    private val validator = EvaluatorTreeValidator(leafSourceCatalog, pipelineAssembler)


    private val drafts = ConcurrentHashMap<String, DraftTreeState>()
    private val EVICTION_MILLIS = 2 * 60 * 60 * 1000L // 2 hours

    override fun createDraft(request: CreateDraftRequest): DraftCreationResult {
        lazyEvict()

        val draftId = nextShortId()
        val expectedNodeIds = validator.collectReferencedLeafNodeIds(request.root)

        val state = DraftTreeState(
            draftId = draftId,
            skeletonRequest = request,
            leafConfigs = ConcurrentHashMap(),
            expectedNodeIds = expectedNodeIds,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        drafts[draftId] = state

        return DraftCreationResult(draftId, state.getMissingNodeIds())
    }

    override fun putDraftLeaf(draftId: String, nodeId: String, leafConfig: EvaluatorLeafConfig): PutLeafResult {
        val state = drafts[draftId] ?: return PutLeafResult(
            validation = ValidationReport(
                ok = false,
                diagnostics = listOf(ConfigDiagnostic("draft_not_found", "草稿不存在或已过期: $draftId"))
            )
        )

        // 单节点严格契约校验
        val validation = validator.validateSingleLeafConfig(leafConfig)
        if (!validation.ok) {
            return PutLeafResult(validation = validation.toAiReport())
        }

        // 写入草稿并更新时间
        state.leafConfigs[nodeId] = leafConfig
        state.updatedAt = System.currentTimeMillis()

        return PutLeafResult(
            validation = ValidationReport(ok = true),
            missingNodeIds = state.getMissingNodeIds()
        )
    }

    override fun validateLeafConfig(leafConfig: EvaluatorLeafConfig): ValidationReport {
        return validator.validateSingleLeafConfig(leafConfig).toAiReport()
    }

    override fun commitDraft(draftId: String): SaveEvaluatorTreeResult {
        val state = drafts[draftId] ?: return SaveEvaluatorTreeResult(
            id = "",
            validation = ValidationReport(
                ok = false,
                diagnostics = listOf(ConfigDiagnostic("draft_not_found", "草稿不存在或已过期: $draftId"))
            )
        )

        val missing = state.getMissingNodeIds()
        if (missing.isNotEmpty()) {
            return SaveEvaluatorTreeResult(
                id = "",
                validation = ValidationReport(
                    ok = false,
                    diagnostics = listOf(ConfigDiagnostic("draft_incomplete", "草稿尚未完成，缺失叶子节点: $missing"))
                )
            )
        }

        val request = state.skeletonRequest
        val fullConfig = EvaluatorTreeConfig(
            bindingType = request.bindingType,
            bindingIds = request.bindingIds,
            root = request.root,
            leafConfigs = state.leafConfigs
        )

        val saveRequest = SaveEvaluatorTreeRequest(
            name = request.name,
            config = fullConfig,
            description = request.description,
            existingId = request.existingId,
            enabled = true,
            managerId = request.managerId
        )

        val result = aiConfigGenerationService.saveEvaluatorTree(saveRequest)
        if (result.validation.ok) {
            drafts.remove(draftId) // 落盘成功，清理草稿
        }
        return result
    }

    override fun getDraftStatus(draftId: String): DraftStatusResult? {
        val state = drafts[draftId] ?: return null
        return DraftStatusResult(
            draftId = state.draftId,
            missingNodeIds = state.getMissingNodeIds(),
            filledNodeIds = state.leafConfigs.keys.toSet(),
            totalExpectedNodes = state.expectedNodeIds.size,
            updatedAt = state.updatedAt
        )
    }

    /**
     * 惰性清理过期草稿，防止内存轻微泄漏
     */
    private fun lazyEvict() {
        val now = System.currentTimeMillis()
        val expiredIds = drafts.entries.filter { (now - it.value.updatedAt) > EVICTION_MILLIS }.map { it.key }
        expiredIds.forEach { drafts.remove(it) }
    }

    private fun EvaluatorTreeValidator.ValidationReport.toAiReport(): ValidationReport {
        return ValidationReport(
            ok = this.ok,
            diagnostics = this.diagnostics.map { d ->
                ConfigDiagnostic(code = d.code, message = d.message, path = d.path)
            }
        )
    }
}
