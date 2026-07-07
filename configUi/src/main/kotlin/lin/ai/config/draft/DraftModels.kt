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
    @field:JsonPropertyDescription("绑定的目标类型")
    val bindingType: EvaluatorTreeBindingType,
    @field:JsonPropertyDescription("绑定的目标 ID 列表")
    val bindingIds: List<String>,
    @field:JsonPropertyDescription("描述")
    val description: String? = null,
    @field:JsonPropertyDescription("覆盖已有配置的ID")
    val existingId: String? = null,
    @field:JsonPropertyDescription("所属卡组 ID")
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
