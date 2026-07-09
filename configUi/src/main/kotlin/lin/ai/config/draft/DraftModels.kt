package lin.ai.config.draft

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import lin.ai.config.ValidationReport
import lin.rule.tree.EvaluatorLeafConfig
import lin.rule.tree.EvaluatorNode
import lin.rule.tree.EvaluatorTreeBindingType

data class CreateDraftRequest(
    @field:JsonPropertyDescription("评估树的名称")
    val name: String,
    @field:JsonPropertyDescription("评估树的拓扑逻辑骨架")
    val root: EvaluatorNode,
    @field:JsonPropertyDescription("绑定的目标类型（GROUP 或 PURPOSE_TAG(暂无对应工具支持)）")
    val bindingType: EvaluatorTreeBindingType,
    @field:JsonPropertyDescription(
        """绑定的目标 ID 列表。
        - 当 bindingType=GROUP 时：必须是某个 card_group_manager 下「绑定条目」的 id（即 save_card_group 响应里的 bindingIds 列表元素），而【不是】manager 自身的 id；可包含多个绑定条目。
        - 当 bindingType=PURPOSE_TAG 时：填写用途标签的 id。"""
    )
    val bindingIds: List<String>,
    @field:JsonPropertyDescription("描述")
    val description: String? = null,
    @field:JsonPropertyDescription("覆盖已有配置的ID")
    val existingId: String? = null,
    @field:JsonPropertyDescription(
        """所属卡组 manager 的 id（GROUP 绑定时【必填】）。
        取值来自 list_card_groups 返回的 id，或 save_card_group 响应的 managerId。注意它不同于 bindingIds 里的绑定条目 id。PURPOSE_TAG 绑定时留空。"""
    )
    val managerId: String? = null
)

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
