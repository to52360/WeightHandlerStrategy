package lin.mcp

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import lin.ai.config.draft.DraftTreeCommand
import lin.rule.tree.EvaluatorNode
import lin.rule.tree.EvaluatorTreeBindingType
import org.junit.Test

/**
 * 合一模式 schema 实验（2026-08-11）：
 * 对比「平铺 flat」vs「多态 discriminated union」在 victools 未开 JSONSUBTYPES 时的实际 JSON Schema 输出。
 * 结论：多态 WRAPPER_OBJECT 能生成规范 anyOf 分支（已落地 create_draft_tree 生产模型 DraftTreeCommand）；
 * 方案 B（EXISTING_PROPERTY）仅作对照组，未采用。
 *
 * 跑完输出两份 schema 供人工对比与长期观察。
 */
class PolymorphicSchemaExperimentTest {

    /** 生产模型：DraftTreeCommand（WRAPPER_OBJECT 多态，已落地到 create_draft_tree） */
    @Test
    fun printDraftTreeCommandSchema() {
        println("===== 生产模型 DraftTreeCommand（多态 WRAPPER_OBJECT）=====")
        println(JsonSchemaUtils.generateSchemaJson(DraftTreeCommand::class.java))
    }

    /** 方案 B：EXISTING_PROPERTY + oneOf 判别（对照组，未采用） */
    @Test
    fun printExistingPropertyPolymorphicSchema() {
        println("===== 多态 schema（方案 B：EXISTING_PROPERTY command 判别）=====")
        println(JsonSchemaUtils.generateSchemaJson(PropDraftCommand::class.java))
    }
}

/* ── 对照组模型：方案 B（EXISTING_PROPERTY command 判别，未采用，仅对比用）── */

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "command")
@JsonSubTypes(
    JsonSubTypes.Type(value = PropCreateDraftCmd::class, name = "create_draft_tree"),
    JsonSubTypes.Type(value = PropDeleteDraftCmd::class, name = "delete_draft_tree")
)
sealed interface PropDraftCommand

data class PropCreateDraftCmd(
    @field:JsonPropertyDescription("命令类型：create_draft_tree（创建草稿）")
    val command: String,
    @field:JsonPropertyDescription("评估树的名称")
    val name: String,
    @field:JsonPropertyDescription("评估树的拓扑逻辑骨架")
    val root: EvaluatorNode? = null,
    @field:JsonPropertyDescription("绑定的目标类型")
    val bindingType: EvaluatorTreeBindingType,
    @field:JsonPropertyDescription("绑定的目标 ID 列表")
    val bindingIds: List<String>,
    @field:JsonPropertyDescription("所属卡组 manager 的 id（GROUP 绑定必填）")
    val managerId: String? = null,
    @field:JsonPropertyDescription("克隆已有评估树的 id")
    val cloneFrom: String? = null,
    @field:JsonPropertyDescription("描述")
    val description: String? = null,
    @field:JsonPropertyDescription("覆盖已有配置的ID")
    val existingId: String? = null
) : PropDraftCommand

data class PropDeleteDraftCmd(
    @field:JsonPropertyDescription("命令类型：delete_draft_tree（删除草稿）")
    val command: String,
    @field:JsonPropertyDescription("要废弃的草稿 id")
    val deleteDraftId: String
) : PropDraftCommand
