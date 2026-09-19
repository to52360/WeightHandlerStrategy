package lin.mcp

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import lin.bean.usePlan.PurposeTagId
import lin.bean.usePlan.PurposeTagIntentRule
import lin.bean.usePlan.UseStage
import lin.mcp.action.*
import lin.repository.card_purpose.*
import lin.rule.tree.EvaluatorTreeBindingType
import lin.ui.card_purpose.PurposeTagProvider
import lin.ui.service.TreeConfigService
import java.time.LocalDate

/**
 * 用途标签（PurposeTag）域 MCP 工具提供者（写工具 + 动作 + DTO 同文件）：
 * - PurposeTagAction：resource=purpose_tag 的 get/list/delete（原 purpose_tag 工具）。
 * - provide()：save_card_purpose 批量打标工具。
 * - DTO（PurposeTagSummaryDto 等）供 list/get 动作共用。
 */

// ── DTO ──

/** 用途标签关联卡牌信息 */
data class PurposeTagCardInfoDto(
    val cardId: String,
    val name: String?,
    val replanAfterUse: Boolean
)

/** 用途标签绑定的评估树摘要 */
data class BoundTreeSummaryDto(
    val id: String,
    val name: String,
    val description: String? = null,
    val bindingType: String = "PURPOSE_TAG",
    val bindingIds: List<String>
)

/** 用途标签摘要 DTO (list) */
data class PurposeTagSummaryDto(
    val tagId: String,
    val displayName: String,
    @field:JsonPropertyDescription("用途绑定：继承哪个战略用途的行为。null = 纯标记（零编排副作用，仅供条件树查询）")
    val boundPurpose: String? = null,
    @field:JsonPropertyDescription(
        "**是否可声明作用**：true = 该标记可被预设 / 卡组增量项声明时序与惜售（内置 5 个作用恒 true；" +
                "自定义标记需先经 save_purpose_tag_def(declarable=true) 晋级）。" +
                "⚠️ 内置 7 个里的 FINISH / EXTRA_COST 恒 false（它们是查询 / 路由标签，无编排行为）。"
    )
    val declarable: Boolean = false,
    @field:JsonPropertyDescription("是否内置战略用途（内置行的 declarable 由代码常量决定，写侧忽略该入参）")
    val builtin: Boolean = false,
    @field:JsonPropertyDescription(
        "**是否有全局规则行**。false = 该用途无规则条目（如 FINISH / EXTRA_COST）⇒ " +
                "下列 defaultStage / priority / N 等字段**只是展示用的内置默认落点，不是生效规则**：" +
                "无规则 = 不参与出牌阶段选优，配置面调它无效。"
    )
    val hasRule: Boolean,
    @field:JsonPropertyDescription("默认映射的出牌阶段（时序命名，非用途）。可选值：FIRST, SETUP, MID, LATE, GENERAL, LAST")
    val defaultStage: String,
    val defaultOrderWeight: Double,
    @field:JsonPropertyDescription("该标签的全局兜底声明：打出后是否必须重新评估。")
    val defaultReplanAfterUse: Boolean,
    val priority: Int,
    @field:JsonPropertyDescription("该标签默认余费门槛 N（惜售声明）。null=未声明回落 0（付得起即垫）；N>0=平时惜售，战术命中（评估树 ts>0）兑现即放行，未命中需空闲 ≥ 牌费+N 才垫")
    val defaultSurplusIdleThreshold: Int? = null,
    val associatedCardCount: Int
)

/** 用途标签详细信息 DTO (get) */
data class PurposeTagDetailDto(
    val tagId: String,
    val displayName: String,
    @field:JsonPropertyDescription("用途绑定：继承哪个战略用途的行为。null = 纯标记（零编排副作用，仅供条件树查询）")
    val boundPurpose: String? = null,
    @field:JsonPropertyDescription(
        "**是否可声明作用**：true = 该标记可被预设 / 卡组增量项声明时序与惜售（内置 5 个作用恒 true；" +
                "自定义标记需先经 save_purpose_tag_def(declarable=true) 晋级）。" +
                "⚠️ 内置 7 个里的 FINISH / EXTRA_COST 恒 false（它们是查询 / 路由标签，无编排行为）。"
    )
    val declarable: Boolean = false,
    @field:JsonPropertyDescription("是否内置战略用途（内置行的 declarable 由代码常量决定，写侧忽略该入参）")
    val builtin: Boolean = false,
    @field:JsonPropertyDescription(
        "**是否有全局规则行**。false = 该用途无规则条目（如 FINISH / EXTRA_COST）⇒ " +
                "下列 defaultStage / priority / N 等字段**只是展示用的内置默认落点，不是生效规则**：" +
                "无规则 = 不参与出牌阶段选优，配置面调它无效。"
    )
    val hasRule: Boolean,
    @field:JsonPropertyDescription("默认映射的出牌阶段（时序命名，非用途）。可选值：FIRST, SETUP, MID, LATE, GENERAL, LAST")
    val defaultStage: String,
    val defaultOrderWeight: Double,
    @field:JsonPropertyDescription("该标签的全局兜底声明：打出后是否必须重新评估。与分组级/卡级 replanAfterUse 取 OR 合并，任一为真即重评估。存在理由：引擎靠手牌数量变化推断状态变化，会漏判「清场」等只改战场不改手牌数的情形")
    val defaultReplanAfterUse: Boolean,
    val priority: Int,
    @field:JsonPropertyDescription("该标签默认余费门槛 N（惜售声明）。null=未声明回落 0（付得起即垫）；N>0=平时惜售，战术命中（评估树 ts>0）兑现即放行，未命中需空闲 ≥ 牌费+N 才垫")
    val defaultSurplusIdleThreshold: Int? = null,
    val associatedCards: List<PurposeTagCardInfoDto>,
    val boundEvaluatorTrees: List<BoundTreeSummaryDto>
)

/** 批量打标输入（save_card_purpose） */
data class SaveCardPurposeInput(
    @field:JsonPropertyDescription("要打标的卡牌 ID 列表（如 [\"BOT_909\"]）。不能为空。")
    val cardIds: List<String>,

    @field:JsonPropertyDescription("最终生效的用途标签 ID 完整集合（覆盖语义，如 [\"DRAW_CARD\"]）。空集合 = 清除该卡全部标签。")
    val purposeTags: List<String> = emptyList(),

    @field:JsonPropertyDescription("使用后是否需要重新规划（replanAfterUse）。null 表示不修改当前值。")
    val replanAfterUse: Boolean? = null
)

/** 标记定义登记输入（save_purpose_tag_def） */
data class SavePurposeTagDefInput(
    @field:JsonPropertyDescription("标记 ID（如 AOE_REMOVAL）。建议大写 + 下划线，与条件树 has_purpose_tag 的查询值保持一致。")
    val tagId: String,

    @field:JsonPropertyDescription("中文显示名（如 \"范围解场\"）。")
    val displayName: String,

    @field:JsonPropertyDescription("可选：用途说明，便于后续回顾这个标记当初为什么建。")
    val description: String? = null,

    @field:JsonPropertyDescription(
        "用途绑定：指向某个战略用途的 tagId。null = 纯标记（零编排副作用，仅供条件树查询）；" +
                "指定 = 继承该战略用途的排序兜底与评估层绑定。" +
                "只允许一层，且目标只能是战略用途（SAVE_LIFE / CLEAN / GREED / FINISH / VALUE / EXTRA_COST / DRAW_CARD），" +
                "禁止绑到自定义标记。"
    )
    val boundPurpose: String? = null,

    @field:JsonPropertyDescription(
        "**晋级为「可声明作用」**：true = 该自定义标记可被预设 / 卡组增量项声明时序与惜售，" +
                "拥有自己的编排行为；false = 降级回不可声明（仍被声明引用时会被拒绝）；null = 不修改。" +
                "⚠️ 与 boundPurpose **互斥**（绑定的语义是「继承某作用的行为」，晋级是「自己就是作用」）——" +
                "同时成立会报错，请先解绑。⚠️ 内置 7 个忽略该入参（其可声明性由代码常量决定）。"
    )
    val declarable: Boolean? = null
)

// ── Provider ──

/**
 * 依赖全部由构造注入（写工具 + 能力 lambda 用到的都在此声明）。
 * `actions` 在构造期求值但 lambda 体不执行 ⇒ 依赖首次调用才解析。
 */
class PurposeTagToolProvider(
    private val tagProvider: PurposeTagProvider,
    private val cardPurposeRepository: CardPurposeRepository,
    private val tagDefRepository: PurposeTagDefRepository,
    /** 展示的是**全局缺省值来源**（声明模型下不等于"当前生效规则"，见 [globalRulesByTag]）。 */
    private val ruleRepository: PurposeTagRuleRepository,
    private val treeConfigService: TreeConfigService,
    private val purposeTagService: PurposeTagService
) : McpToolProvider {

    override val actions: List<ResourceActions> = listOf(
        ResourceActions(
            resource = ActionResources.PURPOSE_TAG,
            capabilities = listOf(
                GetCapability(
                    fieldHint = "用途标签 ID（如 SAVE_LIFE, CLEAN, FINISH, GREED, VALUE, EXTRA_COST；由 list(resource=purpose_tag) 返回）"
                ) { id -> tagDetail(id) },
                ListCapability { tagSummaries() },
                DeleteCapability(
                    fieldHint = "标记 tagId（由 list(resource=purpose_tag) 返回）",
                    semantics = "战略用途（builtin）不可删；仍被卡牌引用的标记不可删（防幽灵标记，需先摘标）；" +
                            "删除前落快照并回 snapshotId，可经 restore_snapshot 一键恢复（原 tagId 保留）",
                    ops = purposeTagService.deleteOps()
                ),
                RestoreCapability { entityId, payload ->
                    purposeTagService.restoreFromSnapshot(entityId, payload)
                }
            )
        )
    )

    override fun provide(): List<McpToolHandler> = listOf(
        // ── save_card_purpose: 批量打标（增删查合一，重复调用幂等）──
        typedTool<SaveCardPurposeInput>(
            name = "save_card_purpose",
            description = """
                批量设置卡牌的战略用途标签（PURPOSE_TAG 打标），并可调整 replanAfterUse。
                重复调用幂等：对同一卡牌以同一 tags 集合再次调用结果不变。

                purposeTags 为【完整覆盖】语义：传入后该卡最终标签 = 传入集合（与 UI 打标"合并 Tag"不同，此处直接整表替换），
                传空集合表示清除该卡全部标签。replanAfterUse 为 null 表示不修改当前值。

                典型场景：给过牌卡打 DRAW_CARD 标签，或给某卡增补/移除用途标签。

                ⚠️ 打标前先确认该标签的编排含义：**打标本身不产生行为**，行为来自「该用途被声明」
                （预设 / 卡组增量项）——被声明后它才带来四项默认值：
                defaultStage / defaultOrderWeight / defaultSurplusIdleThreshold(N) / defaultReplanAfterUse
                （N 多标签取 max、replan 取 any，均属保守方向合并，且不因 stageOverride 而跳过）；
                未被声明 ⇒ 无规则、不参与阶段选优。
                用 get(resource=purpose_tag, id=标签ID) 查看这四项与 declarable 再决定是否打标；
                不需要任何编排行为时用纯查询标签（如 FINISH / EXTRA_COST）。

                标签不存在时先用 save_purpose_tag_def 登记（登记后本工具的标签白名单自动放行）。
                绑定了战略用途的标记会在此处自动展开——打 AOE_REMOVAL 若其绑定 CLEAN，
                最终标签集合为 [AOE_REMOVAL, CLEAN]。
            """.trimIndent()
        ) { input ->
            handleSave(input)
        },
        // ── save_purpose_tag_def: 登记/修改标记定义（T-TG-001，白名单随之可增长）──
        typedTool<SavePurposeTagDefInput>(
            name = "save_purpose_tag_def",
            description = """
                登记或修改一个标记定义（Tag Definition），登记后即可用于 save_card_purpose 与条件树查询。
                按 tagId 覆盖，重复调用幂等。

                三种形态（术语见项目术语表）：
                - boundPurpose = null：**纯标记**，零编排副作用，仅供条件树查询
                  （has_purpose_tag / purpose_filter），不会带来 stage / N / replan 默认值，也不挂评估树。
                - boundPurpose = "CLEAN"：**绑定**战略用途，继承其排序兜底与评估层绑定。
                - declarable = true（仅自定义标记）：**晋级为「可声明作用」**——此后预设 /
                  卡组增量项可对其声明时序与惜售，该标记拥有**自己的**编排行为（可独立配 stage / N）。

                ⚠️ 绑定只允许一层，目标只能是战略用途（内置那 7 个），禁止绑到自定义标记。
                ⚠️ 晋级与绑定**互斥**（继承别人的行为 vs 自己就是作用）：同时传会报错，请先解绑再晋级。
                ⚠️ 降级（declarable = false）时若仍被预设 / 卡组 Delta 的声明引用 ⇒ 报错拒绝。
                内置标记（SAVE_LIFE / CLEAN / GREED / FINISH / VALUE / EXTRA_COST / DRAW_CARD）可改显示名，
                但其 boundPurpose 恒为 null、declarable 由内置清单决定（入参被忽略）。
                `declarable` 的实际取值用 list / get(resource=purpose_tag) 查看。

                ⚠️ 已知限制（实现形态 A：展开式）：改绑定后**已打标的卡不会回溯**——
                展开结果在打标时已写入 card_purpose，需要重新调用 save_card_purpose 才会生效。
            """.trimIndent()
        ) { input ->
            handleSaveTagDef(input)
        }
    )

    private fun handleSave(input: SaveCardPurposeInput): McpToolResult {
        if (input.cardIds.isEmpty()) throw McpBadInput("cardIds 不能为空")

        // 校验标签合法性：仅允许已登记标签，防幻觉打未知 tag（白名单随标记定义落库而可增长）
        val availableTagIds = tagProvider.tags().map { it.id.value }.toSet()
        val unknownTags = input.purposeTags.filter { it !in availableTagIds }
        if (unknownTags.isNotEmpty()) {
            throw McpBadInput(
                "未知用途标签: $unknownTags。当前可用标签: $availableTagIds；" +
                        "确需新增请先用 save_purpose_tag_def 登记"
            )
        }

        // T-TG-001 展开式：把「用途绑定」在落库时拍平进 purpose_tags，引擎侧零改动。
        // 只展开一层——绑定目标恒为战略用途，其自身 boundPurpose 为 null，故天然无法链式。
        val bindingMap = tagDefRepository.bindingMap()
        val effectiveTags = (input.purposeTags + input.purposeTags.mapNotNull { bindingMap[it] }).distinct()

        val now = LocalDate.now().toString()
        val entities = input.cardIds.distinct().map { cardId ->
            val existing = cardPurposeRepository.findByCardId(cardId)
            CardPurposeEntity(
                cardId = cardId,
                name = existing?.name,
                purposeTags = effectiveTags.joinToString(","),
                replanAfterUse = input.replanAfterUse ?: existing?.replanAfterUse ?: false,
                createdDate = existing?.createdDate ?: now
            )
        }
        cardPurposeRepository.saveAll(entities)

        val result = entities.map { e ->
            mapOf(
                "cardId" to e.cardId,
                "name" to e.name,
                "purposeTags" to e.purposeTags.split(",").filter { it.isNotBlank() },
                "replanAfterUse" to e.replanAfterUse
            )
        }
        return mcpSuccess(mapOf("savedCount" to result.size, "cards" to result))
    }

    private fun handleSaveTagDef(input: SavePurposeTagDefInput): McpToolResult {
        // 校验单点在 PurposeTagService.saveTagDef（UI 走同一入口，防两处各写一遍守门而漂移）
        val result = purposeTagService.saveTagDef(
            SaveTagDefCommand(
                tagId = input.tagId,
                displayName = input.displayName,
                description = input.description,
                boundPurpose = input.boundPurpose,
                declarable = input.declarable
            )
        )
        val entity = result.entity ?: throw McpBadInput(result.error ?: "保存标记定义失败")
        return mcpSuccess(
            mapOf(
                "tagId" to entity.tagId,
                "displayName" to entity.displayName,
                "boundPurpose" to entity.boundPurpose,
                "builtin" to entity.builtin,
                "declarable" to entity.declarable
            )
        )
    }

    // ── 能力实现（purpose_tag 的 get / list）：具名私有函数，行为可点名 ──

    /**
     * 全局用途规则行（T-TG-028）：**本页展示的是全局缺省值来源，不是某卡组当前生效的规则** ——
     * 声明模型下生效规则由「卡组引用预设 + 增量项」决定（见 `SqlitePurposeTagIntentRuleProvider`），
     * 而本工具的职责是回答「该标签的出厂默认是什么、新建预设时会被预填成什么」。
     */
    private fun globalRulesByTag() =
        ruleRepository.findAll().mapNotNull { row ->
            runCatching { UseStage.valueOf(row.defaultStage) }.getOrNull()?.let { stage ->
                row.tagId to PurposeTagIntentRule(
                    tagId = PurposeTagId(row.tagId),
                    defaultStage = stage,
                    defaultOrderWeight = row.defaultOrderWeight,
                    priority = row.priority,
                    defaultReplanAfterUse = row.defaultReplanAfterUse,
                    defaultSurplusIdleThreshold = row.defaultSurplusIdleThreshold
                )
            }
        }.toMap()

    /** list：标签摘要（含规则默认值 + 关联卡数）。 */
    private fun tagSummaries(): McpToolResult {
        val tags = tagProvider.tags()
        val rules = globalRulesByTag()
        val defs = tagDefRepository.findAll().associateBy { it.tagId }
        val declarableTagIds = tagDefRepository.declarableTagIds()
        val allCardPurposes = cardPurposeRepository.findAll()
        val cardTagSets = allCardPurposes.map { entity ->
            entity.purposeTags.split(",").map { it.trim() }.toSet()
        }
        val summaries = tags.map { tagDef ->
            val tagIdStr = tagDef.id.value
            val rule = rules[tagIdStr]
            PurposeTagSummaryDto(
                tagId = tagIdStr,
                displayName = tagDef.displayName,
                boundPurpose = defs[tagIdStr]?.boundPurpose,
                declarable = tagIdStr in declarableTagIds,
                builtin = defs[tagIdStr]?.builtin == true,
                hasRule = rule != null,
                defaultStage = rule?.defaultStage?.name ?: "GENERAL",
                defaultOrderWeight = rule?.defaultOrderWeight ?: 0.0,
                defaultReplanAfterUse = rule?.defaultReplanAfterUse ?: false,
                priority = rule?.priority ?: 100,
                defaultSurplusIdleThreshold = rule?.defaultSurplusIdleThreshold,
                associatedCardCount = cardTagSets.count { it.contains(tagIdStr) }
            )
        }
        return mcpSuccess(summaries)
    }

    /** get：标签详情（含规则默认值 + 关联卡 + 绑定评估树）。 */
    private fun tagDetail(id: String): McpToolResult {
        val targetTagId = id.trim()
        val availableTags = tagProvider.tags()
        val availableTagIds = availableTags.map { it.id.value }

        if (targetTagId.isBlank() || targetTagId !in availableTagIds) {
            return mcpError("tagId 不合法。当前可用标签: $availableTagIds")
        }

        val tagDef = availableTags.first { it.id.value == targetTagId }
        val rule = globalRulesByTag()[targetTagId]

        val allCardPurposes = cardPurposeRepository.findAll()
        val associatedCards = allCardPurposes
            .filter { entity ->
                entity.purposeTags.split(",").map { it.trim() }.toSet().contains(targetTagId)
            }
            .map { entity ->
                PurposeTagCardInfoDto(
                    cardId = entity.cardId,
                    name = entity.name,
                    replanAfterUse = entity.replanAfterUse
                )
            }

        val boundTrees = treeConfigService.loadAll()
            .filter { (entity, _) ->
                entity.bindingType == EvaluatorTreeBindingType.PURPOSE_TAG.name &&
                        entity.bindingIds.split(",").map { it.trim() }.toSet().contains(targetTagId)
            }
            .map { (entity, _) ->
                BoundTreeSummaryDto(
                    id = entity.id,
                    name = entity.name,
                    description = entity.description,
                    bindingType = entity.bindingType,
                    bindingIds = entity.bindingIds.split(",").map { it.trim() }
                )
            }

        val detailDef = tagDefRepository.findByTagId(targetTagId)
        val detail = PurposeTagDetailDto(
            tagId = targetTagId,
            displayName = tagDef.displayName,
            boundPurpose = detailDef?.boundPurpose,
            declarable = targetTagId in tagDefRepository.declarableTagIds(),
            builtin = detailDef?.builtin == true,
            hasRule = rule != null,
            defaultStage = rule?.defaultStage?.name ?: "GENERAL",
            defaultOrderWeight = rule?.defaultOrderWeight ?: 0.0,
            defaultReplanAfterUse = rule?.defaultReplanAfterUse ?: false,
            priority = rule?.priority ?: 100,
            defaultSurplusIdleThreshold = rule?.defaultSurplusIdleThreshold,
            associatedCards = associatedCards,
            boundEvaluatorTrees = boundTrees
        )
        return mcpSuccess(detail)
    }
}
