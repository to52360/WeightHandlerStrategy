package lin.ai.config

import lin.rule.parse.SpecValidator
import lin.rule.tree.*
import lin.tree_config.db.EvaluatorLeafSourceCatalog
import lin.ui.service.TreeConfigService

/**
 * 面向 MCP 的配置生成门面。
 * 这里只承接作者侧流程；运行期真正解释配置的契约仍由 WeightHanderStrategy 的树模型决定。
 */
class DefaultAiConfigGenerationService(
    private val leafSourceCatalog: EvaluatorLeafSourceCatalog,
    private val treeConfigService: TreeConfigService
) : AiConfigGenerationService {
    // todo 是否统一套元素数据
    override fun listEvaluatorLeafSources(): List<AiEvaluatorLeafSource> {
        return leafSourceCatalog.loadAll().map { item ->
            AiEvaluatorLeafSource(
                kind = item.kind,
                sourceId = item.sourceId,
                name = item.name,
                desc = item.desc,
                fields = (item.builtInFields + item.fields).map { it.toAiFieldSpec() }
            )
        }
    }

    override fun validateEvaluatorTree(request: SaveEvaluatorTreeRequest): ValidationReport {
        val diagnostics = mutableListOf<ConfigDiagnostic>()
        val leafConfigs = request.config.leafConfigs
        val knownSources = leafSourceCatalog.loadAll().associateBy { it.kind to it.sourceId }

        collectReferencedLeafNodeIds(request.config.root).forEach { nodeId ->
            if (leafConfigs[nodeId] == null) {
                diagnostics += ConfigDiagnostic(
                    code = "missing_leaf_config",
                    message = "评估树节点缺少 leafConfig: nodeId=$nodeId",
                    path = "leafConfigs.$nodeId"
                )
            }
        }

        leafConfigs.values.forEach { leafConfig ->
            validateLeafConfig(leafConfig, knownSources, diagnostics)
        }

        // ARCH-UNSETTLED(ai-config-generator, U-001): 这里先做作者侧结构校验，运行契约校验是否抽到 WeightHanderStrategy 独立 validator 仍需确认 | next: 讨论 EvaluatorTreeContractValidator 的归属和输入依赖
        return ValidationReport(
            ok = diagnostics.isEmpty(),
            diagnostics = diagnostics
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
            existingId = request.existingId,
            enabled = request.enabled,
            managerId = request.managerId,
            isTemplate = request.isTemplate
        )
        return SaveEvaluatorTreeResult(id = id, validation = validation)
    }

    private fun validateLeafConfig(
        leafConfig: EvaluatorLeafConfig,
        knownSources: Map<Pair<EvaluatorLeafKind, String>, EvaluatorLeafMeta>,
        diagnostics: MutableList<ConfigDiagnostic>
    ) {
        val meta = knownSources[leafConfig.kind to leafConfig.sourceId]
        if (meta == null) {
            diagnostics += ConfigDiagnostic(
                code = "unknown_leaf_source",
                message = "未知评估树叶子来源: type=${leafConfig.kind}, sourceId=${leafConfig.sourceId}",
                path = "leafConfigs.${leafConfig.nodeId}.sourceId"
            )
            return
        }

        // 调用底层的 SpecValidator 校验 args
        val allFields = meta.builtInFields + meta.fields
        val validationResult = SpecValidator.validate(leafConfig.args, allFields)
        if (!validationResult.isValid) {
            validationResult.errors.forEach { error ->
                diagnostics += ConfigDiagnostic(
                    code = error.errorCode,
                    message = error.message,
                    path = "leafConfigs.${leafConfig.nodeId}.args.${error.propertyName}"
                )
            }
        }
    }

    private fun collectReferencedLeafNodeIds(root: EvaluatorNode): Set<String> {
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
