package lin.mcp.action

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import lin.mcp.McpToolResult
import lin.mcp.mcpError
import lin.mcp.mcpSuccess
import lin.repository.delete_snapshot.RestoreResult
import lin.repository.delete_snapshot.SnapshotOps
import lin.repository.delete_snapshot.SnapshotRefused

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
 * 资源能力模型（Q-TG-007 / 2026-09-13）——**值化形态**：
 * 一个资源 = 一条 [ResourceActions]（**id 只在这里声明一次**），其 `capabilities` 里放若干**能力值**。
 *
 * - **能力＝类型**：`capabilities.filterIsInstance<GetCapability>()` 即"支持 get"
 *   （Q-007 归一不变：支持面由类型表达，**不用 null / boolean**）。
 * - 能力值零依赖、零状态：业务逻辑由各 Provider 的**具名私有函数**承载（行为可点名），
 *   依赖由 Provider `by inject()` 自取（依赖跨越不因值化而增加）。
 * - 支持面判定：dispatcher 取到 null 即"该 resource 不支持此操作"（报错文案不变）。
 */
sealed interface ResourceCapability

/** get 能力；[fieldHint] 供 `tool_capabilities` 展示 id 语义。 */
data class GetCapability(
    val fieldHint: String,
    val handle: (id: String) -> McpToolResult
) : ResourceCapability

/** list 能力；[supportsManagerIdFilter] 供 `tool_capabilities` 展示过滤面。 */
data class ListCapability(
    val supportsManagerIdFilter: Boolean = false,
    val handle: (managerId: String?) -> McpToolResult
) : ResourceCapability

/**
 * delete 能力（沿用 T-TG-022 Z5 语义）：**只声明"删除前怎么采快照"**——
 * 编排（落快照 + 删除同事务 + 回显 `snapshotId`）在 `DeleteDispatcher` 单点，机制在 `SnapshotStore`。
 */
data class DeleteCapability(
    /** delete 的 id 字段语义说明（`tool_capabilities` 展示用）。 */
    val fieldHint: String,
    /** 删除语义说明（引用校验 / 恢复快照 / 级联等，`tool_capabilities` 展示用）。 */
    val semantics: String,
    /**
     * 本资源的删除操作值（采集 + 校验 + 删除体），通常由域服务 `deleteOps()` 导出。
     * 直接是**值**（不是 `() -> SnapshotOps` 闭包）：Provider 构造期构建一次即可，
     * dispatcher 无需再缓存（去一层间接 + 去一个缓存可变状态）。
     */
    val ops: SnapshotOps
) : ResourceCapability

/** 快照恢复能力：按快照内容写回（原 id 保留）；失败（如原 id 已被占用）返回 `isError = true`。 */
data class RestoreCapability(
    /** 恢复体，通常转调本域服务（`XxxService.restoreFromSnapshot`）。 */
    val handle: (entityId: String, payload: String) -> RestoreResult
) : ResourceCapability

/**
 * 一个资源的动作集合 —— **`resource` 的唯一声明处**（防多能力各写一次 id 造成不一致）。
 * `capabilities` 里放了哪些能力，该资源就支持哪些操作。
 */
data class ResourceActions(
    val resource: String,
    val capabilities: List<ResourceCapability>
)

/** 取本资源的某能力（无则 null）；能力面判定单点，替代原 `as?`。 */
inline fun <reified T : ResourceCapability> ResourceActions.capability(): T? =
    capabilities.filterIsInstance<T>().firstOrNull()

/**
 * delete 动作的统一入口：领域服务以 [SnapshotRefused] 表达"拒绝删除"（实体不存在 / 引用中禁删），
 * 此处转成 MCP 错误结果 —— 让 `DeleteDispatcher` 的删除编排保持一行（业务校验全在域服务 / guard 里）。
 */
internal inline fun mcpDeleteAction(block: () -> Map<String, Any?>): McpToolResult =
    try {
        mcpSuccess(block())
    } catch (e: SnapshotRefused) {
        mcpError(e.message ?: "删除被拒绝")
    }

/** get 大工具输入：resource 判别 + 通用 id（语义因资源而异，用 tool_capabilities 详情查询确认）。id 可空以让 resource 校验优先于 id 缺失报错。 */
data class GetInput(
    @field:JsonPropertyDescription("资源类型。支持面由本进程动态注册，用 tool_capabilities 查支持矩阵与各资源 id 语义。")
    val resource: String,
    @field:JsonPropertyDescription("资源标识。各资源的 id 语义不同，用 tool_capabilities 详情确认。")
    val id: String? = null
)

/** list 大工具输入：resource 判别 + 可选 managerId 过滤。 */
data class ListInput(
    @field:JsonPropertyDescription("资源类型。支持面由本进程动态注册，用 tool_capabilities 查支持矩阵。")
    val resource: String,
    @field:JsonPropertyDescription("可选：按卡组 managerId 过滤。仅部分资源支持，支持面用 tool_capabilities 查询。")
    val managerId: String? = null
)

/** delete 大工具输入：resource 判别 + 通用 id。id 可空以让 resource 校验优先于 id 缺失报错。 */
data class DeleteInput(
    @field:JsonPropertyDescription("资源类型。支持面由本进程动态注册，用 tool_capabilities 查支持矩阵与各资源删除语义。")
    val resource: String,
    @field:JsonPropertyDescription("资源标识。各资源的 id 语义不同，用 tool_capabilities 详情确认。")
    val id: String? = null
)

/** tool_capabilities 输入：不传 resource = 支持矩阵，传 resource = 单资源操作详情。 */
data class CapabilitiesInput(
    @field:JsonPropertyDescription("可选：资源类型。不传返回全部资源的 get/list/delete 支持矩阵；传则返回该资源的操作详情（id 语义 / 过滤字段 / 删除语义）。")
    val resource: String? = null
)
