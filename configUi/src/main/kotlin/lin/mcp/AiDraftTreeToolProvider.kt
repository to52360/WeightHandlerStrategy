package lin.mcp

import lin.ai.config.draft.*

/**
 * 负责评估树生成的渐进式/草稿池 MCP 工具暴露。
 * 代替原先单一且极易出错的 save_evaluator_tree。
 */
class AiDraftTreeToolProvider(
    private val draftTreeService: DraftTreeService
) : McpToolProvider {
    override fun provide(): List<McpToolHandler> = listOf(
        typedTool<CreateDraftRequest>(
            name = "create_draft_tree",
            description = "创建一个评估树草稿骨架。由于评估树 JSON 结构极大，必须采用渐进式生成：先用此工具建立拓扑逻辑骨架，它会返回 draftId 以及预期需要填写的 missingNodeIds。如果有不满意的拓扑，不需要修改，直接用这个工具重新创建一个新的即可。\n\n【克隆模式】若提供 cloneFrom（已有树配置 id，由 evaluator_tree(action=LIST) 获取），将以该配置为蓝本创建草稿：所有叶子节点参数预填，missingNodeIds 为空，可直接 commit 原样克隆；也可用 put_draft_leaf 覆盖差异节点再提交。\n\n后续请使用 put_draft_leaf 工具逐个填充叶子节点。"
        ) { request ->
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
            description = "查询某个评估树草稿的当前进度，返回已填节点、未填节点和总数。当对话中断或上下文截断后恢复时，用此工具确认草稿还缺哪些叶子需要继续填写。"
        ) { request ->
            val result = draftTreeService.getDraftStatus(request.draftId)
                ?: return@typedTool mcpError("草稿不存在或已过期: ${request.draftId}")
            mcpSuccess(result)
        }
    )
}
