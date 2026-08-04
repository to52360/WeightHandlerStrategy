package lin.ai.config.draft

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import lin.ai.config.ValidationReport
import lin.rule.tree.EvaluatorLeafConfig
import lin.rule.tree.EvaluatorNode
import lin.rule.tree.EvaluatorTreeBindingType

data class CreateDraftRequest(
    @field:JsonPropertyDescription("评估树的名称")
    val name: String,
    @field:JsonPropertyDescription(
        """评估树的拓扑逻辑骨架。与 cloneFrom 互斥：从零创建模式必填；提供 cloneFrom 时留空（使用克隆源的骨架）。
        
【JSON 格式要求—WRAPPER_OBJECT 多态】系统使用 Jackson WRAPPER_OBJECT 序列化，四种节点的 key 分别是: "AndNode" / "OrNode" / "BranchNode" / "Leaf"。每个节点的 payload 是 "Rule" 或 "BranchCondition" 包裹的含 nodeId 对象。评估树【不支持】NOT 节点（取反仅用于条件树）。
        
示例:
- 单叶子: {"Leaf":{"payload":{"Rule":{"nodeId":"r1"}}}}
- AND(两个子): {"AndNode":{"children":[{"Leaf":{"payload":{"Rule":{"nodeId":"a"}}}},{"Leaf":{"payload":{"Rule":{"nodeId":"b"}}}}]}}
- OR: {"OrNode":{"children":[...]}}
- Branch: {"BranchNode":{"payload":{"BranchCondition":{"nodeId":"c1"}},"onTrue":{...},"onFalse":{...}}}"""
    )
    val root: EvaluatorNode? = null,
    @field:JsonPropertyDescription("绑定的目标类型（GROUP, PURPOSE_TAG 或 CARD）")
    val bindingType: EvaluatorTreeBindingType,
    @field:JsonPropertyDescription(
        """绑定的目标 ID 列表。
        - 当 bindingType=GROUP 时：必须是某个 card_group_manager 下「绑定条目」的 id（即 save_card_group 响应里的 bindingIds 列表元素），而【不是】manager 自身的 id；可包含多个绑定条目。
        - 当 bindingType=CARD 时：必须是卡牌的 ID 列表（如 ["BT_020"]）。
        - 当 bindingType=PURPOSE_TAG 时：填写用途标签的 id。"""
    )
    val bindingIds: List<String>,
    @field:JsonPropertyDescription("描述")
    val description: String? = null,
    @field:JsonPropertyDescription("覆盖已有配置的ID")
    val existingId: String? = null,
    @field:JsonPropertyDescription(
        """所属卡组 manager 的 id（GROUP 绑定时【必填】）。
        取值来自 list_card_groups 返回的 id，或 save_card_group 响应的 managerId。注意它不同于 bindingIds 里的绑定条目 id。PURPOSE_TAG 或 CARD 绑定时留空。"""
    )
    val managerId: String? = null,
    @field:JsonPropertyDescription("克隆已有评估树的 id（由 evaluator_tree(action=LIST) 获取）。与 root 互斥：提供 cloneFrom 时 root 留空。非空时以该配置为蓝本创建草稿，叶子节点参数预填，missingNodeIds 为空，可直接 commit 或用 put_draft_leaf 覆盖差异节点。")
    val cloneFrom: String? = null
) {
    fun toQuery(): DraftCreationQuery {
        val cloneFrom = cloneFrom
        return if (cloneFrom != null) {
            if (root != null) throw IllegalArgumentException("root 与 cloneFrom 互斥，不可同时提供")
            DraftCreationQuery.CloneDraft(cloneFrom)
        } else {
            DraftCreationQuery.NewDraft(
                root ?: throw IllegalArgumentException("root 不能为空（非克隆模式请提供 root，克隆模式请提供 cloneFrom）")
            )
        }
    }
}

/**
 * 草稿创建意图的 sealed 域模型：用编译期类型区分「从零创建」与「克隆」，
 * 消除 [CreateDraftRequest.root] / [CreateDraftRequest.cloneFrom] 的伪可选可空。
 * 扁平 JSON 载体仍是 [CreateDraftRequest]，由 [CreateDraftRequest.toQuery] 在边界转换。
 */
sealed interface DraftCreationQuery {
    data class NewDraft(val root: EvaluatorNode) : DraftCreationQuery
    data class CloneDraft(val configId: String) : DraftCreationQuery
}

data class PutDraftLeafRequest(
    @field:JsonPropertyDescription("草稿 ID")
    val draftId: String,
    @field:JsonPropertyDescription("节点 ID")
    val nodeId: String,
    @field:JsonPropertyDescription("叶子节点配置")
    val leafConfig: EvaluatorLeafConfig
)

data class CommitDraftRequest(
    @field:JsonPropertyDescription("草稿 ID")
    val draftId: String
)

data class GetDraftStatusRequest(
    @field:JsonPropertyDescription("草稿 ID")
    val draftId: String
)

data class AbandonDraftRequest(
    @field:JsonPropertyDescription("要废弃的草稿 ID")
    val draftId: String
)

data class DraftTreeState(
    val draftId: String,
    val skeletonRequest: CreateDraftRequest,
    val leafConfigs: MutableMap<String, EvaluatorLeafConfig>,
    val expectedNodeIds: Set<String>,
    val createdAt: Long,
    var updatedAt: Long
) {
    fun getMissingNodeIds(): Set<String> = expectedNodeIds - leafConfigs.keys
}

data class DraftCreationResult(
    val draftId: String,
    val missingNodeIds: Set<String>
)

data class PutLeafResult(
    val validation: ValidationReport,
    val missingNodeIds: Set<String>? = null
)

data class DraftStatusResult(
    val draftId: String,
    val missingNodeIds: Set<String>,
    val filledNodeIds: Set<String>,
    val totalExpectedNodes: Int,
    val updatedAt: Long
)
