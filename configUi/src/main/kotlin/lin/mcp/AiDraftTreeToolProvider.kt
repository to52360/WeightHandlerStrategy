package lin.mcp

import lin.ai.config.draft.*
import lin.repository.card_group.CardGroupService
import lin.rule.tree.EvaluatorTreeBindingType

/**
 * 负责评估树生成的渐进式/草稿池 MCP 工具暴露。
 * 代替原先单一且极易出错的 save_evaluator_tree。
 */
class AiDraftTreeToolProvider(
    private val draftTreeService: DraftTreeService,
    private val cardGroupService: CardGroupService
) : McpToolProvider {
    override fun provide(): List<McpToolHandler> = listOf(
        typedTool<CreateDraftRequest>(
            name = "create_draft_tree",
            description = """创建一个评估树草稿骨架。评估树 JSON 结构极大，必须采用渐进式生成：
                                1. 用此工具建立拓扑骨架，返回 draftId 和 missingNodeIds
                                2. 用 put_draft_leaf 逐个填充叶子节点
                                3. 全部填完后用 commit_draft_tree 提交
                                
                                拓扑不满意时无需修改，重新创建一个新草稿即可。
                                
                                【两种互斥模式】
                                - 从零创建：提供 root（拓扑骨架）
                                - 克隆已有树：提供 cloneFrom（已有配置 id），叶子参数预填，可直接 commit 或用 put_draft_leaf 覆盖差异节点
                            """
        ) { request ->
            // 提前校验 GROUP 绑定的引用完整性，避免填入全部叶子后才在 commit 时被拒
            if (request.bindingType == EvaluatorTreeBindingType.GROUP) {
                val managerId = request.managerId
                    ?: return@typedTool mcpError("bindingType=GROUP 时 managerId 必填（取值来自 card_group(action=LIST) 或 save_card_group 响应的 managerId）")
                val allManagers = cardGroupService.loadAll(onlyEnabled = true)
                val manager = allManagers.firstOrNull { it.cardGroupManagerId == managerId }
                    ?: return@typedTool mcpError("卡组方案不存在或未启用: $managerId。可用 card_group(action=LIST) 查看现有方案。")
                val validBindingIds = manager.bindings.map { it.id }.toSet()
                val invalidIds = request.bindingIds.filter { it !in validBindingIds }
                if (invalidIds.isNotEmpty()) {
                    return@typedTool mcpError(
                        "以下 bindingIds 不属于卡组方案 \"${manager.name}\": $invalidIds。" +
                                "该方案的有效绑定条目为: ${manager.bindings.map { "${it.name}(${it.id})" }}"
                    )
                }
            }
            val result = draftTreeService.createDraft(request)
            mcpSuccess(result)
        },
        typedTool<PutDraftLeafRequest>(
            name = "put_draft_leaf",
            description = "向评估树草稿中填入单个叶子节点配置。如果验证失败会直接报错并拒绝写入；成功则会返回剩余未填的 missingNodeIds。如果之前填错了某个节点，用同样的 draftId 和 nodeId 再次调用即可覆盖原有的错误叶子。"
        ) { request ->
            val result = draftTreeService.putDraftLeaf(request.draftId, request.nodeId, request.leafConfig)
            if (!result.validation.ok) {
                mcpError("Leaf validation failed: ${result.validation.diagnostics}")
            } else {
                mcpSuccess(result)
            }
        },
        typedTool<CommitDraftRequest>(
            name = "commit_draft_tree",
            description = "提交评估树草稿。只有当所有的 missingNodeIds 全部被成功填入并且为空时，才能成功提交并保存至数据库。成功后草稿将被清理并完成评估树的生成任务。"
        ) { request ->
            val result = draftTreeService.commitDraft(request.draftId)
            if (!result.validation.ok) {
                mcpError("Commit failed: ${result.validation.diagnostics}")
            } else {
                mcpSuccess(result)
            }
        },
        typedTool<GetDraftStatusRequest>(
            name = "get_draft_status",
            description = "查询某个评估树草稿的当前进度，返回已填节点、未填节点和总数。当对话中断或上下文截断后恢复时，用此工具确认草稿还缺哪些叶子需要继续填写。注意：草稿有生命周期，进程重启/长期搁置后会过期（返回不存在），过期草稿无法续填，需重新 create_draft_tree。"
        ) { request ->
            val result = draftTreeService.getDraftStatus(request.draftId)
                ?: return@typedTool mcpError("草稿不存在或已过期: ${request.draftId}")
            mcpSuccess(result)
        },
        typedTool<AbandonDraftRequest>(
            name = "abandon_draft",
            description = "废弃一个不再需要的评估树草稿（如绑定校验失败、拓扑设计错误等）。被废弃的草稿将立即从内存中清除。"
        ) { request ->
            val removed = draftTreeService.abandonDraft(request.draftId)
            if (removed) {
                mcpSuccess(mapOf("abandoned" to true, "draftId" to request.draftId))
            } else {
                mcpError("草稿不存在或已过期: ${request.draftId}")
            }
        }
    )
}
