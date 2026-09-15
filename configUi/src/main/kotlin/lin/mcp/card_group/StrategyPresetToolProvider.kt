package lin.mcp.card_group

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import lin.bean.usePlan.UseStage
import lin.mcp.*
import lin.mcp.action.*
import lin.repository.card_group.DimensionItemResolver
import lin.repository.card_group.StrategyPresetService
import lin.repository.card_group.SurplusOverride
import lin.repository.card_group.ThresholdPatch
import lin.repository.card_group.TimingOverride
import lin.repository.tree_config.TreeConfigRepository
import lin.rule.tree.EvaluatorTreeBindingType
import lin.ui.service.TreeConfigService

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

    @field:JsonPropertyDescription("默认出牌阶段。可选 FIRST/SETUP/MID/LATE/GENERAL/LAST；不传 = 用全局规则行的值。")
    val defaultStage: String? = null,

    @field:JsonPropertyDescription("默认排序权重。不传 = 用全局规则行的值。")
    val defaultOrderWeight: Double? = null,

    @field:JsonPropertyDescription("打出后是否必须重新评估。不传 = 用全局规则行的值。")
    val defaultReplanAfterUse: Boolean? = null,

    @field:JsonPropertyDescription(
        "优先级（一张卡同时挂多个用途时用来裁决 stage 等唯一字段，数值越大越优先）。" +
                "**不传 = 用该用途全局规则行的 priority**（无全局行则用内置默认 100）。" +
                "priority 只在一张卡内部比较、不跨卡，故可按预设分化。"
    )
    val priority: Int? = null
)

/**
 * 用途**惜售声明**（独立维度 `PURPOSE_SURPLUS`，T-TG-029）—— 与 [PurposeTimingInput] 分开传。
 *
 * 语义：某用途的牌「平时惜售，等余费足够才垫」的门槛 N（与"何时出"是两件事）。
 */
data class PurposeSurplusInput(
    @field:JsonPropertyDescription("用途标签 ID（如 CLEAN / SAVE_LIFE，由 list(resource=purpose_tag) 返回）。")
    val tagId: String,

    @field:JsonPropertyDescription(
        "余费门槛 N（正整数）：平时**不进第一轮主组合**，战术命中（评估树战术分 > 0）直接放行；" +
                "未命中需空闲余费 ≥ 牌费 + N 才进填充。不传 = 用全局规则行的值。"
    )
    val defaultSurplusIdleThreshold: Int? = null,

    @field:JsonPropertyDescription(
        "把余费门槛声明为「不设门槛」（付得起即垫）。与 defaultSurplusIdleThreshold 互斥（同时传会报错）。" +
                "⚠️ 只有本开关能表达「声明为无门槛」—— 不传任何门槛字段只表示「用全局规则行的值」。"
    )
    val clearSurplusIdleThreshold: Boolean = false
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
        "本预设对用途的**时序声明**（**整体替换**语义）。不传 = 不修改；传空数组 = 清空。" +
                "⚠️ 声明即规则：**声明的用途 = 有规则（参与 priority 选优），未声明的用途 = 无规则**" +
                "（阶段回落 GENERAL），与树白名单同向。" +
                "声明里没写的字段用全局 purpose_tag_rule 行的值兜底。" +
                "⚠️ 惜售门槛 N **不在此处**（T-TG-029 起独立维度）—— 用 surplus 参数声明。"
    )
    val timings: List<PurposeTimingInput>? = null,

    @field:JsonPropertyDescription(
        "本预设对用途的**惜售声明**（独立维度，**整体替换**语义）。不传 = 不修改；传空数组 = 清空。" +
                "⚠️ 在这里声明某用途 = 该用途**有规则**（与 timings 同效）；门槛没写 = 回落全局行。"
    )
    val surplus: List<PurposeSurplusInput>? = null
)

data class CloneStrategyPresetInput(
    @field:JsonPropertyDescription("源预设 id（由 list(resource=strategy_preset) 返回）；其两个维度会被复制到新预设。")
    val sourcePresetId: String,

    @field:JsonPropertyDescription("新预设名称（如 \"快攻-铺场特化\"）。")
    val name: String,

    @field:JsonPropertyDescription("可选：新预设说明。不传 = 沿用源预设的说明。")
    val description: String? = null
)

data class SaveCardGroupPresetInput(
    @field:JsonPropertyDescription("卡组 managerId。")
    val managerId: String,

    @field:JsonPropertyDescription(
        "该卡组引用的预设 id；不传或空 = 不用预设。" +
                "⚠️ 不用预设 = **无任何声明**（合法的「精准规则」场景）⇒ 该卡组**用途树不生效、时序无规则**；" +
                "引用**空预设** = 声明了但一无所有 ⇒ 用途树全禁（与前者在树维度同效，但语义不同）。"
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
        "本卡组对用途时序的声明（压过预设值；**整体替换**语义）。不传 = 不修改；传空数组 = 清空。"
    )
    val timings: List<PurposeTimingInput>? = null,

    @field:JsonPropertyDescription(
        "本卡组对用途**惜售门槛**的声明（T-TG-029 独立维度；压过预设值；**整体替换**语义）。" +
                "不传 = 不修改；传空数组 = 清空。"
    )
    val surplus: List<PurposeSurplusInput>? = null
)

// ── Provider ──

class StrategyPresetToolProvider(
    private val service: StrategyPresetService,
    /** 前置校验用：树必须存在、必须是 PURPOSE_TAG 绑定、且确实绑了该用途。 */
    private val treeRepository: TreeConfigRepository,
    /** 「被禁用的用途清单」计算（纯规则）。 */
    private val resolver: DimensionItemResolver,
    /** 全局用途树候选与用途全集口径（T-TG-024）。 */
    private val treeConfigService: TreeConfigService
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

                三个维度（**同向语义：声明 = 生效，未声明 = 不生效**）：
                - **treeSelections（白名单）**：某用途**保留**哪些树。
                  ⚠️ 未列出的用途 = 未声明 ⇒ **该用途的用途树全部禁用**（响应里的 disabledPurposes 会列出）。
                - **timings（时序声明）**：某用途的 stage / orderWeight / replan（+ 可选 priority）。
                  ⚠️ **声明即规则**：未声明的用途 = **无规则**（不参与 priority 选优，阶段回落 GENERAL），
                  与树白名单同向；声明里没写的**字段**才回落全局 purpose_tag_rule 行。
                - **surplus（惜售门槛声明，独立于时序的维度）**：`defaultSurplusIdleThreshold` = 声明为门槛 N；
                  `clearSurplusIdleThreshold` = 声明为「不设门槛」；两者都不传 = 未声明（回落全局 rule 行的默认门槛）。
                  ⚠️ 未声明与「声明为无门槛」语义相反：前者用全局默认，后者强制不设门槛。

                粒度：树维度是 **(用途, 树)** —— 一棵树绑了多个用途时，各自独立取舍（实现是按用途收窄绑定）。

                ⚠️ 解析链为「卡组私有（组级/卡级） > 消费方增量项 > 预设声明 > 全局行（仅作缺省值） > 内置默认」；
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
            val surplus = input.surplus?.associate { it.toDomain() }

            val result = service.savePreset(
                presetId = input.presetId?.trim()?.takeIf { it.isNotBlank() },
                name = input.name.trim(),
                description = input.description,
                treeSelections = treeSelections,
                timings = timings,
                surplus = surplus
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
                    "surplusCount" to result.surplusCount,
                    "disabledPurposes" to disabledPurposes(declared)
                )
            )
        },

        typedTool<CloneStrategyPresetInput>(
            name = "clone_strategy_preset",
            description = """
                从现有预设**派生**一个新预设（fork）—— 复制源预设的「树白名单 + 时序覆盖」两个维度，
                用于「快攻通用 / 控制通用」这类**分类通用预设**的快速建站（否则每建一个预设都要重填一遍时序）。

                ⚠️ **fork = 只复制内容、不建立关系**：新预设与源预设此后**各自独立演化** ——
                改源预设**不会**传导到已派生的预设（"改通用份、派生份跟着变"目前不支持，见 D-TG-017）。
                ⚠️ 源预设**漏声明的用途会被一并继承**（未声明 = 该用途树全禁）⇒ 务必核对回显的 disabledPurposes。

                派生后仍需 save_card_group_preset 让卡组引用它；切换预设后**需重启**引擎装配。
            """.trimIndent()
        ) { input ->
            if (input.name.isBlank()) throw McpBadInput("name 不能为空")
            if (input.name.trim().length > 60) throw McpBadInput("name 过长（<= 60）")
            val sourceId = input.sourcePresetId.trim()
            if (sourceId.isBlank()) throw McpBadInput("sourcePresetId 不能为空")

            val result = service.clonePreset(
                sourceId = sourceId,
                name = input.name.trim(),
                description = input.description
            ) ?: return@typedTool mcpError("派生失败：源预设不存在（sourcePresetId=$sourceId）")

            val declared = service.findDetail(result.presetId)?.treeSelections?.keys.orEmpty()
            mcpSuccess(
                mapOf(
                    "presetId" to result.presetId,
                    "sourcePresetId" to sourceId,
                    "name" to result.name,
                    "treeItemCount" to result.treeItemCount,
                    "timingCount" to result.timingCount,
                    "surplusCount" to result.surplusCount,
                    "disabledPurposes" to disabledPurposes(declared)
                )
            )
        },

        typedTool<SaveCardGroupPresetInput>(
            name = "save_card_group_preset",
            description = """
                设置卡组引用的用途预设（不传 presetId = 不用预设）。

                ⚠️ 语义（声明模型）：
                - **不引用预设** = **一条声明都没有** ⇒ 该卡组**全局用途树不生效、时序无规则**
                  （合法的「精准规则」场景 —— 只靠卡组私有分组/评估树；**卡组专属用途树不受影响**，建树即声明）；
                - **引用空预设** = 引用了但没声明任何内容 ⇒ 同样全禁（语义更明确，推荐用「不引用」表达精准意图）。
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
                - **timings**：本卡组对该用途的**时序声明**（逐字段压过预设声明；也可**自行声明预设没声明的用途**）；
                - **surplus**：本卡组对该用途的**惜售门槛**声明（T-TG-029 独立维度，同"压过预设"语义）。

                ⚠️ 这是"共享预设 + 本卡组微调"的正规通道：想整套换掉请改用「不引用预设 + 自己的精细分组」，
                  而不是在这里逐项排除；**整体替换**语义（不传 = 不改 / 空数组 = 清空）。
                ⚠️ 未声明的用途 = 无规则（阶段回落 GENERAL），声明里没写的字段回落全局行。
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
            val surplus = input.surplus?.associate { it.toDomain() }
            val error = service.saveDeckDelta(
                managerId = input.managerId.trim(),
                treeExclusions = excludes,
                timings = timings,
                surplus = surplus
            )
            if (error != null) return@typedTool mcpError(error)
            mcpSuccess(
                mapOf(
                    "managerId" to input.managerId.trim(),
                    "excludedTreeCount" to excludes?.values?.sumOf { it.size },
                    "timingCount" to timings?.size,
                    "surplusCount" to surplus?.size
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
     * 校验并转换为 [TimingOverride]；stage 非法 / priority 越界直接报错（防脏数据让引擎侧静默丢弃）。
     *
     * ⚠️ **不校验「有全局规则行」**（T-TG-028 起声明即规则）：未声明的用途**可以**被声明，
     * 未写的字段才回落全局行，全局无行则用内置默认（D-TG-018）。
     */
    private fun PurposeTimingInput.toDomain(): Pair<String, TimingOverride> {
        val tag = tagId.trim()
        if (tag.isBlank()) throw McpBadInput("timings[].tagId 不能为空")
        val stage = defaultStage?.trim()?.takeIf { it.isNotBlank() }
        if (stage != null && UseStage.entries.none { it.name == stage }) {
            throw McpBadInput(
                "timings[$tag].defaultStage 非法: $stage。" +
                        "可选值: ${UseStage.entries.joinToString("/") { it.name }}"
            )
        }
        if (priority != null && priority !in PRIORITY_RANGE) {
            throw McpBadInput("timings[$tag].priority 超出范围: $priority（可填 ${PRIORITY_RANGE.first}~${PRIORITY_RANGE.last}）")
        }
        return tag to TimingOverride(
            defaultStage = stage,
            defaultOrderWeight = defaultOrderWeight,
            defaultReplanAfterUse = defaultReplanAfterUse,
            priority = priority
        )
    }

    /**
     * 校验并转换为 [SurplusOverride]（T-TG-029：惜售是与时序**并列的独立维度**）。
     *
     * 三态：不传任何门槛字段 = 未声明（回落全局行）；传 N = 声明为 N；`clearSurplusIdleThreshold` = 声明为无门槛。
     */
    private fun PurposeSurplusInput.toDomain(): Pair<String, SurplusOverride> {
        val tag = tagId.trim()
        if (tag.isBlank()) throw McpBadInput("surplus[].tagId 不能为空")
        if (clearSurplusIdleThreshold && defaultSurplusIdleThreshold != null) {
            throw McpBadInput("surplus[$tag]: clearSurplusIdleThreshold 与 defaultSurplusIdleThreshold 互斥")
        }
        if (defaultSurplusIdleThreshold != null && defaultSurplusIdleThreshold < 1) {
            throw McpBadInput("surplus[$tag].defaultSurplusIdleThreshold 必须为正整数: $defaultSurplusIdleThreshold")
        }
        val threshold = when {
            clearSurplusIdleThreshold -> ThresholdPatch(null)
            defaultSurplusIdleThreshold != null -> ThresholdPatch(defaultSurplusIdleThreshold)
            else -> null
        }
        return tag to SurplusOverride(surplusIdleThreshold = threshold)
    }

    /** 「被禁用的用途清单」= 库中已有用途树（已启用）的用途 − 本预设已声明的用途。 */
    private fun disabledPurposes(declaredTags: Set<String>): List<String> {
        val universe = purposeTagUniverse()
        return resolver.disabledPurposes(universe, declaredTags)
    }

    /** 库中已被用途树绑定的用途全集（仅已启用的全局 `PURPOSE_TAG` 树，见 D-TG-015 / T-TG-024）。 */
    private fun purposeTagUniverse(): Set<String> = treeConfigService.purposeTagUniverse()

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
                "surplusCount" to s.surplusCount,
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
        val universe = purposeTagUniverse()
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
                        "defaultReplanAfterUse" to override.defaultReplanAfterUse,
                        // 未声明 ⇒ null（引擎侧取全局行的 priority，无全局行则内置默认 100）
                        "priority" to override.priority
                    )
                },
                // T-TG-029：惜售是**独立维度**，单列（不再混在 timings 里）
                "surplus" to detail.surplus.map { (tag, override) ->
                    mapOf(
                        "tagId" to tag,
                        "defaultSurplusIdleThreshold" to override.surplusIdleThreshold?.value,
                        "surplusIdleThresholdCleared" to (override.surplusIdleThreshold != null &&
                                override.surplusIdleThreshold.value == null)
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
        /** priority 合法区间：只用于挡明显误填（0 会让该用途在任何冲突中垫底，负值无意义）。 */
        val PRIORITY_RANGE = 1..9999
    }
}
