package lin.mcp.action

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import lin.mcp.McpToolResult

/**
 * 动作大类资源类型常量（单一事实来源：dispatcher / tool_capabilities / 各 handler 共用，防散落字符串）。
 */
object ActionResources {
    const val EVALUATOR_TREE = "evaluator_tree"
    const val COMBO_PLAN = "combo_plan"
    const val CARD_GROUP = "card_group"
    const val CARD_POOL = "card_pool"
    const val CONDITION_TREE = "condition_tree"
    const val AURA_BOOST = "aura_boost"
    const val PURPOSE_TAG = "purpose_tag"
    const val STRATEGY_PRESET = "strategy_preset"
    const val TREE_TEMPLATE = "tree_template"
    const val DRAFT = "draft"
    const val CAPABILITY_BACKGROUND = "capability_background"
    const val DELETE_SNAPSHOT = "delete_snapshot"
}

/**
 * 资源动作（ResourceAction）：一个 resource 一个实现类，通过实现 GetAction / ListAction / DeleteAction
 * 子接口声明支持面（Q-007 归一：支持面 = 实现了哪些子接口，天然消灭 *Supported 布尔与占位异常）。
 * 不支持的入口由 dispatcher 用 as? 判断拒绝（不会调用对应 handle*）。
 * 动作逻辑作为 Provider 内部私有类，与写工具同文件（减少项目结构，一个资源域一个 Provider 文件）。
 */
interface ResourceAction {
    val resource: String
}

/** get 动作：实现此接口即声明支持 get。 */
interface GetAction : ResourceAction {
    /** get 的 id 字段语义说明（tool_capabilities 展示用）。 */
    val getFieldHint: String get() = "id 参数（语义见 handleGet 实现）"

    fun handleGet(id: String): McpToolResult
}

/** list 动作：实现此接口即声明支持 list。 */
interface ListAction : ResourceAction {
    /** list 是否支持按 managerId 过滤（tool_capabilities 二级详情展示用，默认不支持）。 */
    val supportsManagerIdFilter: Boolean get() = false

    fun handleList(managerId: String?): McpToolResult
}

/** delete 动作：实现此接口即声明支持 delete。 */
interface DeleteAction : ResourceAction {
    /** delete 的 id 字段语义说明（tool_capabilities 展示用）。 */
    val deleteFieldHint: String get() = "id 参数（语义见 handleDelete 实现）"

    /** 删除语义说明（引用校验/恢复快照/级联等，tool_capabilities 展示用）。 */
    val deleteSemantics: String get() = ""

    fun handleDelete(id: String): McpToolResult
}

/** get 大工具输入：resource 判别 + 通用 id（语义因资源而异，用 tool_capabilities 详情查询确认）。id 可空以让 resource 校验优先于 id 缺失报错。 */
data class GetInput(
    @field:JsonPropertyDescription("资源类型。支持：evaluator_tree / combo_plan / card_group / card_pool / condition_tree / aura_boost / purpose_tag / strategy_preset / tree_template / draft。完整支持面与各资源 id 语义用 tool_capabilities 查询。")
    val resource: String,
    @field:JsonPropertyDescription("资源标识。id 语义因资源而异（card_pool=fileName、card_group=managerId、purpose_tag=tagId、draft=draftId、其余=资源 id），务必先用 tool_capabilities 确认。")
    val id: String? = null
)

/** list 大工具输入：resource 判别 + 可选 managerId 过滤。 */
data class ListInput(
    @field:JsonPropertyDescription("资源类型。支持：evaluator_tree / combo_plan / card_group / card_pool / condition_tree / aura_boost / purpose_tag / strategy_preset / tree_template / capability_background。完整支持面用 tool_capabilities 查询。")
    val resource: String,
    @field:JsonPropertyDescription("可选：按卡组 managerId 过滤（evaluator_tree / combo_plan / condition_tree / aura_boost / capability_background 支持，其余资源忽略此字段）。")
    val managerId: String? = null
)

/** delete 大工具输入：resource 判别 + 通用 id。id 可空以让 resource 校验优先于 id 缺失报错。 */
data class DeleteInput(
    @field:JsonPropertyDescription("资源类型。支持：evaluator_tree / combo_plan / card_group / card_pool / condition_tree / aura_boost / purpose_tag。完整支持面与各资源删除语义用 tool_capabilities 查询。")
    val resource: String,
    @field:JsonPropertyDescription("资源标识 id（card_pool=fileName、card_group=managerId、其余=资源 id）。")
    val id: String? = null
)

/** tool_capabilities 输入：不传 resource = 支持矩阵，传 resource = 单资源操作详情。 */
data class CapabilitiesInput(
    @field:JsonPropertyDescription("可选：资源类型。不传返回全部资源的 get/list/delete 支持矩阵；传则返回该资源的操作详情（id 语义 / 过滤字段 / 删除语义）。")
    val resource: String? = null
)
