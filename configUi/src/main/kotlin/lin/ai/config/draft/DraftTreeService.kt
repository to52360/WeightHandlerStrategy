package lin.ai.config.draft

import lin.ai.config.SaveEvaluatorTreeResult
import lin.ai.config.ValidationReport
import lin.rule.tree.EvaluatorLeafConfig

/**
 * 内存级评估树草稿池服务。
 * 支持单节点增量填写和最后的全量组装校验，避免 AI 单次全量生成大 JSON 带来的稳定性和上下文截断问题。
 */
interface DraftTreeService {

    /**
     * 1. 创建骨架暂存，返回 draftId 与预期要填写的 nodeId 列表
     */
    fun createDraft(request: CreateDraftTreeCmd): DraftCreationResult

    /**
     * 2. 增量更新单个叶子节点配置。
     * 若节点校验不通过，直接在 ValidationReport 中返回（Fail-Fast），不会存入草稿池中。
     * 若校验通过，返回的 missingNodeIds 代表还要填哪些节点。
     */
    fun putDraftLeaf(draftId: String, nodeId: String, leafConfig: EvaluatorLeafConfig): PutLeafResult

    /**
     * 3. 校验单个叶子节点配置（无状态校验，方便 AI 试错）
     */
    fun validateLeafConfig(leafConfig: EvaluatorLeafConfig): ValidationReport

    /**
     * 4. 组装最终配置进行全量校验并落盘正式库。成功后将移除此草稿。
     */
    fun commitDraft(draftId: String): SaveEvaluatorTreeResult

    /**
     * 5. 查询草稿状态
     */
    fun getDraftStatus(draftId: String): DraftStatusResult?

    /**
     * 6. 废弃草稿（主动清理不再需要的草稿，如绑定校验失败后放弃）。
     * @return true 表示存在并已删除，false 表示草稿不存在或已过期
     */
    fun abandonDraft(draftId: String): Boolean
}
