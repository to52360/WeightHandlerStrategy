package lin.mcp

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import lin.mcp.action.*
import lin.repository.aura_boost.AuraBoostConfigService
import lin.repository.aura_boost.AuraBoostEntity
import lin.repository.aura_boost.SaveAuraBoostInlineInput
import lin.repository.card_group.Dimension
import lin.repository.card_group.DimensionPayloadCodec
import lin.repository.card_group.DimensionScope
import lin.repository.card_group.StrategyPresetRepository
import lin.repository.delete_snapshot.SnapshotOps
import lin.repository.delete_snapshot.SnapshotRefused

/**
 * Push 广播评分配置（aura-boost）域 MCP 工具提供者（写工具 + 动作同文件）：
 * - resource=aura_boost 的 get/list/delete（原 aura_boost / delete_aura_boost 工具）。
 * - provide()：save_aura_boost 写工具。
 *
 * AuraBoost = 触发条件树（conditionId，全局检测）命中后，给 targetConditionId（受益卡过滤）命中的卡加分（Q-024 后即**费值**）。
 * additive 独立通道：命中费值与评估树费值相加；光环加分只走 AuraBoost，评估树不写光环条件（D-004）。
 * managerId 为消费方归属（卡组级配置），引用的条件树是全局资源（D-003）。
 * T-011：内联建树（0~2 棵）+ boost 行多步写的编排与事务已下沉至
 * [lin.repository.aura_boost.AuraBoostConfigService.saveWithInlineTrees]，Provider 不再持有
 * TransactionTemplate / ConditionTreeConfigService。
 *
 * D-DP-002 追加：**删除悬空守卫** —— 全局行被预设白名单 / 卡组增量项引用时拒删（见 [guardedDeleteOps]）；
 * 全局行自 D-DP-001 起是「候选池」，不再隐式对所有启用卡组生效。
 */
class AuraBoostToolProvider(
    private val service: AuraBoostConfigService,
    /** 悬空守卫用（D-DP-002）：扫描 `AURA_BOOST` 维度项，判断该光环行是否仍被预设 / 卡组声明引用。 */
    private val presetRepository: StrategyPresetRepository
) : McpToolProvider {

    override val actions: List<ResourceActions> = listOf(
        ResourceActions(
            resource = ActionResources.AURA_BOOST,
            capabilities = listOf(
                GetCapability(
                    fieldHint = "AuraBoost id（8 位短 id，由 list(resource=aura_boost) 返回）"
                ) { id -> auraBoostDetail(id) },
                ListCapability(supportsManagerIdFilter = true) { managerId -> auraBoostSummaries(managerId) },
                DeleteCapability(
                    fieldHint = "AuraBoost id（由 list(resource=aura_boost) 返回）",
                    semantics = "**仍被预设白名单 / 卡组增量项引用时拒绝删除**（回显引用方）；" +
                            "删除前落快照（delete_snapshot）并回 snapshotId，可经 restore_snapshot 一键恢复（原 id 保留）",
                    ops = guardedDeleteOps()
                ),
                RestoreCapability { entityId, payload ->
                    service.restoreFromSnapshot(entityId, payload)
                }
            )
        )
    )

    /**
     * 删除操作值 = 域服务 ops **外包一层悬空守卫**（D-DP-002）：
     * 目标行若仍被任何 `AURA_BOOST` 维度项引用（预设白名单 / 卡组 extra / exclude / scoreOverrides）
     * ⇒ 拒绝并回显引用方 —— 否则这些声明会退化为「引擎仍生效、写侧拒绝再编辑」的半悬空态。
     *
     * 为什么跨域扫描放 MCP 层：与 condition_tree 的引用扫描同款分工（T-TG-010 拍板：跨域引用扫描属
     * MCP 层路由职责），避免 `aura_boost` 域反向依赖 `card_group` 域。
     */
    private fun guardedDeleteOps(): SnapshotOps {
        val delegate = service.deleteOps()
        return SnapshotOps(
            collect = { entityId ->
                val refs = auraReferences(entityId)
                if (refs.isNotEmpty()) {
                    throw SnapshotRefused(
                        "AuraBoost $entityId 仍被 ${refs.size} 处引用，删除会让这些声明失去载体" +
                                "（引擎仍生效、写侧拒绝再编辑）：\n" +
                                refs.joinToString("\n") { "  - $it" } +
                                "\n请先在预设 / 卡组 Delta 中移除该引用"
                    )
                }
                delegate.collect(entityId)
            },
            remove = { entityId -> delegate.remove(entityId) }
        )
    }

    /** 引用方清单（预设侧读白名单 payload、消费侧读增量 payload）。 */
    private fun auraReferences(id: String): List<String> =
        presetRepository.findAllByDimension(Dimension.AURA_BOOST).mapNotNull { item ->
            val referenced = if (item.scope == DimensionScope.PRESET) {
                id in DimensionPayloadCodec.decodeAuraSelection(item.payload)
            } else {
                val delta = DimensionPayloadCodec.decodeAuraDelta(item.payload)
                id in delta.extra || id in delta.exclude || id in delta.scoreOverrides
            }
            if (referenced) "${item.scope} ${item.ownerId}" else null
        }

    override fun provide(): List<McpToolHandler> = listOf(
        typedTool<SaveAuraBoostMcpInput>(
            name = "save_aura_boost",
            description = """
                创建或更新一条 Push 广播评分配置（AuraBoost）。
                语义：触发条件树命中时，给受益过滤条件树命中的卡 +score（**费值**）。
                additive 通道：光环加分与评估树加分相加；光环加分只走 AuraBoost，评估树不写光环条件（防双倍计分）。

                【条件树两种提供方式（二选一，互斥）】
                - 复用已有条件树：conditionId / targetConditionId 传已有树 id（来自 list(resource=condition_tree)）
                - 一次性内联创建：conditionTreeJson / targetConditionTreeJson 直接传条件树 JSON（{id,name,root}），
                  无需先 save_condition_tree 建模板，本工具自动建树并返回新 id

                managerId 关联卡组（消费方归属）；引用的条件树是全局资源。传 existingId 更新已有配置。

                【生效口径（全局行 = 候选池）】
                - managerId = null（**全局行**）：不再对所有启用卡组隐式生效 —— 需被**预设的 AURA_BOOST 白名单**
                  纳入（或卡组增量的 extra 补声明）才生效；**未引用预设的卡组没有全局光环**。
                - managerId = 某卡组（**私有行**）：归属即拥有，只对该卡组生效、不受白名单约束。

                enabled 启用开关（缺省保持原值，新建默认 true）：false = 配置仍留在库中可查，
                但**不进引擎**（临时停用通道）。⚠️ 这是**行级**开关，只能停用"这一条规则"。
            """.trimIndent()
        ) { input ->
            val result = service.saveWithInlineTrees(
                SaveAuraBoostInlineInput(
                    name = input.name,
                    conditionId = input.conditionId,
                    conditionTreeJson = input.conditionTreeJson,
                    targetConditionId = input.targetConditionId,
                    targetConditionTreeJson = input.targetConditionTreeJson,
                    score = input.score,
                    managerId = input.managerId,
                    existingId = input.existingId,
                    enabled = input.enabled
                )
            )
            mcpSuccess(
                mapOf(
                    "id" to result.id, "name" to input.name, "score" to input.score,
                    "conditionId" to result.conditionId, "targetConditionId" to result.targetConditionId,
                    "enabled" to (input.enabled ?: true)
                )
            )
        }
    )

    // ── 能力实现（aura_boost 的 get / list）：具名私有函数，行为可点名 ──

    /** list：AuraBoost 摘要（可按 managerId 过滤）。 */
    private fun auraBoostSummaries(managerId: String?): McpToolResult {
        val list = service.loadAll()
            .filter { managerId == null || it.managerId == managerId }
        return mcpSuccess(list.map { it.toSummary() })
    }

    /** get：AuraBoost 详情。 */
    private fun auraBoostDetail(id: String): McpToolResult {
        val entity = service.findById(id)
            ?: return mcpError("AuraBoost 不存在: $id")
        return mcpSuccess(entity.toSummary())
    }
}

private fun AuraBoostEntity.toSummary(): Map<String, Any?> = mapOf(
    "id" to id,
    "name" to name,
    "conditionId" to conditionId,
    "targetConditionId" to targetConditionId,
    "score" to score,
    "managerId" to managerId,
    "enabled" to enabled
)

/**
 * save_aura_boost 的 MCP 专用扁平 input（不直接复用 repository 的 SaveAuraBoostInput，
 * 因为后者 conditionId/targetConditionId 为必填，无法表达"内联创建"分支）。
 * conditionId 与 conditionTreeJson 互斥（同 targetConditionId / targetConditionTreeJson）。
 */
private data class SaveAuraBoostMcpInput(
    @field:JsonPropertyDescription("配置名称。")
    val name: String? = null,
    @field:JsonPropertyDescription("触发条件树 id（复用已有树）。与 conditionTreeJson 互斥：提供 conditionTreeJson 时此字段留空。")
    val conditionId: String? = null,
    @field:JsonPropertyDescription("触发条件树内联 JSON（一次性树，无需先建模板）：完整条件树 JSON 文本 {id,name,root}，root 为节点对象。与 conditionId 互斥：提供此字段时自动建树。")
    val conditionTreeJson: String? = null,
    @field:JsonPropertyDescription("受益过滤条件树 id（复用已有树）。与 targetConditionTreeJson 互斥。")
    val targetConditionId: String? = null,
    @field:JsonPropertyDescription("受益过滤条件树内联 JSON（一次性树，无需先建模板）：完整条件树 JSON 文本 {id,name,root}。与 targetConditionId 互斥：提供此字段时自动建树。")
    val targetConditionTreeJson: String? = null,
    @field:JsonPropertyDescription("命中后加给受益卡的费值（按「≈ 该场景典型卡的等效费」标定）。")
    val score: Double,
    @field:JsonPropertyDescription("归属卡组 managerId（可选，来自 list(resource=card_group)）。不传 = 全局行（候选池，需被预设白名单纳入才生效）。")
    val managerId: String? = null,
    @field:JsonPropertyDescription("可选：更新已有 AuraBoost 时传其 id；不传则新建。")
    val existingId: String? = null,
    @field:JsonPropertyDescription("可选：启用开关。false = 留在库中可查但不进引擎（临时停用）；不传=保持原值，新建默认 true。注意是行级开关，不能按卡组启停。")
    val enabled: Boolean? = null
)
