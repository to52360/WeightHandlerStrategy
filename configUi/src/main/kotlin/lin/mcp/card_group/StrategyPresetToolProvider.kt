package lin.mcp.card_group

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import lin.bean.usePlan.UseStage
import lin.mcp.*
import lin.mcp.action.*
import lin.repository.card_group.DimensionItemResolver
import lin.repository.card_group.StrategyPresetService
import lin.repository.card_group.ThresholdPatch
import lin.repository.card_group.TimingOverride
import lin.repository.card_purpose.PurposeTagRuleRepository
import lin.repository.tree_config.TreeConfigRepository
import lin.rule.tree.EvaluatorTreeBindingType

/**
 * 用途预设域 MCP 工具提供者（T-TG-015）—— 方案见
 * `cross-dialogue/Q-TG-003-use-preset-final.md`。
 *
 * - 预设（`scope=PRESET`）：`save_strategy_preset` —— 按用途声明「**保留哪些树** + 时序覆盖」；
 * - 卡组增量项（`scope=CARD_GROUP`）：`save_card_group_preset_delta` —— 在预设之上**只减 + 覆盖**；
 * - 引用：`save_card_group_preset` —— 只设 `preset_id`，不动增量项；
 * - `resource=strategy_preset` 的 get / list。
 *
 * - `resource=strategy_preset` 的 delete：**仍被卡组引用时拒绝**（回显引用卡组，见 D-TG-010），落快照可恢复。
 */

// ── 输入 DTO ──

data class PresetTreeKeepInput(
    @field:JsonPropertyDescription("用途标签 ID（如 CLEAN / DRAW_CARD）。")
    val tagId: String,

    @field:JsonPropertyDescription(
        "该用途**保留**的评估树 id 列表（白名单）。" +
                "树必须是 `bindingType=PURPOSE_TAG` 且绑定含该用途，否则报错。" +
                "未列出的树对该用途不生效；传空数组 = 该用途一棵树都不要。"
    )
    val treeIds: List<String> = emptyList()
)

data class DeckTreeExcludeInput(
    @field:JsonPropertyDescription("用途标签 ID（如 CLEAN / DRAW_CARD）。")
    val tagId: String,

    @field:JsonPropertyDescription(
        "本卡组在该用途下**额外排除**的评估树 id 列表（在预设白名单之上再减）。传空数组 = 不排除。"
    )
    val treeIds: List<String> = emptyList()
)

data class PurposeTimingInput(
    @field:JsonPropertyDescription("用途标签 ID（如 CLEAN / DRAW_CARD，由 list(resource=purpose_tag) 返回）。")
    val tagId: String,

    @field:JsonPropertyDescription("覆盖：默认出牌阶段。可选 FIRST/SETUP/MID/LATE/GENERAL/LAST；不传 = 不覆盖该字段。")
    val defaultStage: String? = null,

    @field:JsonPropertyDescription("覆盖：默认排序权重。不传 = 不覆盖。")
    val defaultOrderWeight: Double? = null,

    @field:JsonPropertyDescription("覆盖：默认余费门槛 N（正整数）。不传 = 不覆盖该字段。")
    val defaultSurplusIdleThreshold: Int? = null,

    @field:JsonPropertyDescription(
        "覆盖：把余费门槛 N 置为「不设门槛」。与 defaultSurplusIdleThreshold 互斥（同时传会报错）。" +
                "⚠️ 只有本开关能表达「覆盖为无门槛」—— 不传任何门槛字段只表示「不覆盖」。"
    )
    val clearSurplusIdleThreshold: Boolean = false,

    @field:JsonPropertyDescription("覆盖：打出后是否必须重新评估。不传 = 不覆盖。")
    val defaultReplanAfterUse: Boolean? = null
)

data class SaveStrategyPresetInput(
    @field:JsonPropertyDescription("预设 id：不传 = 新建；传 = 更新该预设。")
    val presetId: String? = null,

    @field:JsonPropertyDescription("预设名称（如 \"快攻-标准\"）。")
    val name: String,

    @field:JsonPropertyDescription("可选：预设说明。")
    val description: String? = null,

    @field:JsonPropertyDescription(
        "本预设按用途**保留**的评估树（**整体替换**语义）。不传 = 不修改现有声明；传空数组 = 清空。" +
                "⚠️ **未列出的用途 = 未声明 ⇒ 该用途的用途树全部禁用**（白名单语义）：" +
                "引用本预设的卡组会**接管全部用途的树可见性**，漏声明一个用途就等于关掉它的兜底树 —— " +
                "响应里的 disabledPurposes 会列出被禁用的用途，务必核对。"
    )
    val treeSelections: List<PresetTreeKeepInput>? = null,

    @field:JsonPropertyDescription(
        "本预设对用途的时序覆盖（**整体替换**语义）。不传 = 不修改；传空数组 = 清空。" +
                "只能覆盖**已有全局规则行**的用途（无规则行的用途报错）。"
    )
    val timings: List<PurposeTimingInput>? = null
)

data class SaveCardGroupPresetInput(
    @field:JsonPropertyDescription("卡组 managerId。")
    val managerId: String,

    @field:JsonPropertyDescription(
        "该卡组引用的预设 id；不传或空 = 不用预设。" +
                "⚠️ 不用预设 ⇒ 该卡组**全部用途树生效**（现状）；引用**空预设**才是「关掉全部兜底」。"
    )
    val presetId: String? = null
)

data class SaveCardGroupPresetDeltaInput(
    @field:JsonPropertyDescription("卡组 managerId。")
    val managerId: String,

    @field:JsonPropertyDescription(
        "本卡组**额外排除**的用途树（在预设白名单之上再减；**整体替换**语义）。" +
                "不传 = 不修改；传空数组 = 清空。⚠️ 只能减，不能「启用」被预设禁用的树。"
    )
    val excludeTreeSelections: List<DeckTreeExcludeInput>? = null,

    @field:JsonPropertyDescription(
        "本卡组对用途时序的覆盖（压过预设值；**整体替换**语义）。不传 = 不修改；传空数组 = 清空。"
    )
    val timings: List<PurposeTimingInput>? = null
)

// ── Provider ──

class StrategyPresetToolProvider(
    private val service: StrategyPresetService,
    /** 前置校验用：时序覆盖只能作用于「已有全局规则行」的用途（见 D-TG-003）。 */
    private val ruleRepository: PurposeTagRuleRepository,
    /** 前置校验用：树必须存在、必须是 PURPOSE_TAG 绑定、且确实绑了该用途。 */
    private val treeRepository: TreeConfigRepository,
    /** 「被禁用的用途清单」计算（纯规则）。 */
    private val resolver: DimensionItemResolver
) : McpToolProvider {

    override val actions: List<ResourceActions> = listOf(
        ResourceActions(
            resource = ActionResources.STRATEGY_PRESET,
            capabilities = listOf(
                GetCapability(
                    fieldHint = "预设 id（由 list(resource=strategy_preset) 返回）"
                ) { id -> presetDetail(id) },
                ListCapability { presetSummaries() },
                DeleteCapability(
                    fieldHint = "预设 id（由 list(resource=strategy_preset) 返回）",
                    semantics = "仍被卡组引用时**拒绝删除**（回显引用卡组，需先 save_card_group_preset 不带 presetId 解除引用）；" +
                            "删除前落快照（delete_snapshot）并回 snapshotId，可经 restore_snapshot 一键恢复（原 id 保留，含两维度项）",
                    ops = service.deleteOps()
                ),
                RestoreCapability { entityId, payload ->
                    service.restoreFromSnapshot(entityId, payload)
                }
            )
        )
    )

    override fun provide(): List<McpToolHandler> = listOf(
        typedTool<SaveStrategyPresetInput>(
            name = "save_strategy_preset",
            description = """
                新建/更新一个**用途预设** —— 按用途声明「**保留哪些评估树** + 时序覆盖」，
                由卡组经 save_card_group_preset 引用，用于**快速出一套卡组策略**（兜底层）。

                与「精细层」的分工：逐卡/逐组的精细调整走卡组私有分组，预设只管跨卡组共享的用途级兜底。

                两个维度：
                - **treeSelections（白名单）**：某用途**保留**哪些树。
                  ⚠️ 未列出的用途 = 未声明 ⇒ **该用途的用途树全部禁用**（响应里的 disabledPurposes 会列出）。
                - **timings（覆盖）**：按用途覆盖 stage / orderWeight / N / replan；
                  不传的字段**不覆盖**（回落全局 purpose_tag_rule）；把 N 覆盖为「不设门槛」用 clearSurplusIdleThreshold。

                粒度：树维度是 **(用途, 树)** —— 一棵树绑了多个用途时，各自独立取舍（实现是按用途收窄绑定）。

                ⚠️ 解析链为「卡组私有（组级/卡级） > 消费方增量项 > 预设 > 全局用途规则」；
                  切换预设或卡组后**需重启**引擎装配（配置侧装配期一次性解析）。
                删除预设走 delete(resource=strategy_preset)：**仍被卡组引用时拒绝**（回显引用卡组），
                  需先 save_card_group_preset（不带 presetId）解除引用；删除前落快照，可 restore_snapshot 恢复。
            """.trimIndent()
        ) { input ->
            if (input.name.isBlank()) throw McpBadInput("name 不能为空")
            if (input.name.trim().length > 60) throw McpBadInput("name 过长（<= 60）")

            val treeSelections = input.treeSelections?.associate { item ->
                val tag = item.tagId.trim()
                if (tag.isBlank()) throw McpBadInput("treeSelections[].tagId 不能为空")
                item.treeIds.map { it.trim() }.forEach { treeId -> validateTreeBinding(tag, treeId) }
                tag to item.treeIds.map { it.trim() }.distinct()
            }
            val timings = input.timings?.associate { it.toDomain() }
            timings?.let { validateTimingTargets(it) }

            val result = service.savePreset(
                presetId = input.presetId?.trim()?.takeIf { it.isNotBlank() },
                name = input.name.trim(),
                description = input.description,
                treeSelections = treeSelections,
                timings = timings
            ) ?: return@typedTool mcpError("更新失败：预设不存在（presetId=${input.presetId}）")

            // 树维度**已声明**的用途从**保存后的库态**取（含空数组声明的行）——
            // 不能用本次输入的 keys：更新预设只传 timings（treeSelections 省略 = 不改）时，
            // 输入 keys 为空会把存量声明误报成「全部用途被禁用」。
            val declared = service.findDetail(result.presetId)?.treeSelections?.keys.orEmpty()
            mcpSuccess(
                mapOf(
                    "presetId" to result.presetId,
                    "name" to result.name,
                    "treeItemCount" to result.treeItemCount,
                    "timingCount" to result.timingCount,
                    "disabledPurposes" to disabledPurposes(declared)
                )
            )
        },

        typedTool<SaveCardGroupPresetInput>(
            name = "save_card_group_preset",
            description = """
                设置卡组引用的用途预设（不传 presetId = 不用预设）。

                ⚠️ 语义差别：
                - **不引用预设** = **全部用途树生效**（＝不做兜底裁剪，现状行为）；
                - **引用空预设** = **关闭全部用途树**（即"我不要兜底策略、我要精准策略"的正规表达）。
                ⚠️ 本操作只改引用，不动卡组的分组/元信息、也不动增量项；切换后需重启引擎装配才生效。
            """.trimIndent()
        ) { input ->
            if (input.managerId.isBlank()) throw McpBadInput("managerId 不能为空")
            val error = service.setCardGroupPreset(
                managerId = input.managerId.trim(),
                presetId = input.presetId?.trim()?.takeIf { it.isNotBlank() }
            )
            if (error != null) return@typedTool mcpError(error)
            mcpSuccess(mapOf("managerId" to input.managerId.trim(), "presetId" to input.presetId))
        },

        typedTool<SaveCardGroupPresetDeltaInput>(
            name = "save_card_group_preset_delta",
            description = """
                设置卡组的**用途增量项**（③微调层）—— 在引用的预设之上做小偏差调整。

                - **excludeTreeSelections**：本卡组在某用途下**再排除**若干棵树（只能减，不能启用被预设禁用的树）；
                - **timings**：本卡组对该用途时序的覆盖（逐字段压过预设值）。

                ⚠️ 这是"共享预设 + 本卡组微调"的正规通道：想整套换掉请改用「空预设 + 自己的精细分组」，
                  而不是在这里逐项排除；**整体替换**语义（不传 = 不改 / 空数组 = 清空）。
                ⚠️ 改完需重启引擎装配才生效。
            """.trimIndent()
        ) { input ->
            if (input.managerId.isBlank()) throw McpBadInput("managerId 不能为空")
            val excludes = input.excludeTreeSelections?.associate { item ->
                val tag = item.tagId.trim()
                if (tag.isBlank()) throw McpBadInput("excludeTreeSelections[].tagId 不能为空")
                tag to item.treeIds.map { it.trim() }.distinct()
            }
            val timings = input.timings?.associate { it.toDomain() }
            timings?.let { validateTimingTargets(it) }
            val error = service.saveDeckDelta(
                managerId = input.managerId.trim(),
                treeExclusions = excludes,
                timings = timings
            )
            if (error != null) return@typedTool mcpError(error)
            mcpSuccess(
                mapOf(
                    "managerId" to input.managerId.trim(),
                    "excludedTreeCount" to excludes?.values?.sumOf { it.size },
                    "timingCount" to timings?.size
                )
            )
        }
    )

    // ── 校验与转换 ──

    /** 树必须存在、必须是 `PURPOSE_TAG` 绑定、且确实绑了该用途（"CLEAN 只能选 CLEAN 的树"）。 */
    private fun validateTreeBinding(tagId: String, treeId: String) {
        if (treeId.isBlank()) throw McpBadInput("treeIds 含空 id")
        val tree = treeRepository.findById(treeId) ?: throw McpBadInput("评估树不存在: $treeId")
        if (tree.bindingType != EvaluatorTreeBindingType.PURPOSE_TAG.name) {
            throw McpBadInput("评估树 $treeId 不是用途绑定（bindingType=${tree.bindingType}），不能进用途预设")
        }
        if (tagId !in tree.bindingIdList) {
            throw McpBadInput(
                "评估树 $treeId 没有绑定用途 $tagId（其绑定=${tree.bindingIdList}）—— 只能选绑了该用途的树"
            )
        }
    }

    /**
     * 时序覆盖只能作用于「**已有全局规则行**」的用途 —— 无规则行 = 不参与 priority 选优（D-TG-003），
     * 新增行会改变选优集合且默认 priority 会与他人平手 ⇒ 必须在 MCP 层报错而非静默失效。
     */
    private fun validateTimingTargets(timings: Map<String, TimingOverride>) {
        timings.keys.forEach { tag ->
            if (ruleRepository.findByTagId(tag) == null) {
                throw McpBadInput(
                    "用途 $tag 没有全局规则行，无法覆盖其时序。" +
                            "该用途当前不参与排序默认值推导（无规则 = 不参与，见 D-TG-003）；" +
                            "若确需其有默认值，请先为其建立全局规则。"
                )
            }
        }
    }

    /** 「被禁用的用途清单」= 库中已有用途树（已启用）的用途 − 本预设已声明的用途。 */
    private fun disabledPurposes(declaredTags: Set<String>): List<String> {
        val universe = purposeTagUniverse()
        return resolver.disabledPurposes(universe, declaredTags)
    }

    /** 库中已被用途树绑定的用途全集（已启用的 `PURPOSE_TAG` 树）。 */
    private fun purposeTagUniverse(): Set<String> = treeRepository.findAll()
        .filter { it.enabled && it.bindingType == EvaluatorTreeBindingType.PURPOSE_TAG.name }
        .flatMap { it.bindingIdList }
        .toSet()

    // ── 能力实现（strategy_preset 的 get / list）：具名私有函数，行为可点名 ──

    /** list：预设摘要（含引用它的卡组）。预设是全局资产（不归属任何卡组），managerId 参数不适用。 */
    private fun presetSummaries(): McpToolResult {
        val summaries = service.listPresets().map { s ->
            mapOf(
                "id" to s.preset.id,
                "name" to s.preset.name,
                "description" to s.preset.description,
                "treeItemCount" to s.treeItemCount,
                "timingCount" to s.timingCount,
                "referencedBy" to s.referencedBy.map {
                    mapOf(
                        "managerId" to it.managerId,
                        "name" to it.managerName
                    )
                },
                "createdAt" to s.preset.createdAt
            )
        }
        return mcpSuccess(summaries)
    }

    /** get：预设详情（树白名单 + 时序覆盖 + 被禁用用途 + 引用卡组）。 */
    private fun presetDetail(id: String): McpToolResult {
        val detail = service.findDetail(id.trim())
            ?: return mcpError("预设不存在: $id")
        val universe = treeRepository.findAll()
            .filter { it.enabled && it.bindingType == EvaluatorTreeBindingType.PURPOSE_TAG.name }
            .flatMap { it.bindingIdList }
            .toSet()
        return mcpSuccess(
            mapOf(
                "id" to detail.preset.id,
                "name" to detail.preset.name,
                "description" to detail.preset.description,
                "createdAt" to detail.preset.createdAt,
                "treeSelections" to detail.treeSelections.map { (tag, treeIds) ->
                    mapOf("tagId" to tag, "treeIds" to treeIds.sorted())
                },
                "timings" to detail.timings.map { (tag, override) ->
                    mapOf(
                        "tagId" to tag,
                        "defaultStage" to override.defaultStage,
                        "defaultOrderWeight" to override.defaultOrderWeight,
                        "defaultSurplusIdleThreshold" to override.surplusIdleThreshold?.value,
                        "surplusIdleThresholdCleared" to (override.surplusIdleThreshold?.value == null &&
                                override.surplusIdleThreshold != null),
                        "defaultReplanAfterUse" to override.defaultReplanAfterUse
                    )
                },
                // 防"以为在用其实被禁"：列出被本预设禁用的用途
                "disabledPurposes" to resolver.disabledPurposes(universe, detail.treeSelections.keys),
                "referencedBy" to service.findReferences(detail.preset.id)
                    .map { mapOf("managerId" to it.managerId, "name" to it.managerName) }
            )
        )
    }

    private companion object {
        /** 校验并转换为 [TimingOverride]；stage 非法直接报错（防脏数据让引擎侧静默丢弃）。 */
        fun PurposeTimingInput.toDomain(): Pair<String, TimingOverride> {
            val tag = tagId.trim()
            if (tag.isBlank()) throw McpBadInput("timings[].tagId 不能为空")
            if (clearSurplusIdleThreshold && defaultSurplusIdleThreshold != null) {
                throw McpBadInput("$tag: clearSurplusIdleThreshold 与 defaultSurplusIdleThreshold 互斥")
            }
            val stage = defaultStage?.trim()?.takeIf { it.isNotBlank() }
            if (stage != null && UseStage.entries.none { it.name == stage }) {
                throw McpBadInput(
                    "timings[$tag].defaultStage 非法: $stage。" +
                            "可选值: ${UseStage.entries.joinToString("/") { it.name }}"
                )
            }
            val threshold = when {
                clearSurplusIdleThreshold -> ThresholdPatch(null)
                defaultSurplusIdleThreshold != null -> ThresholdPatch(defaultSurplusIdleThreshold)
                else -> null
            }
            return tag to TimingOverride(
                defaultStage = stage,
                defaultOrderWeight = defaultOrderWeight,
                defaultReplanAfterUse = defaultReplanAfterUse,
                surplusIdleThreshold = threshold
            )
        }
    }
}
