package lin.mcp

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import lin.bean.usePlan.PurposeTagIntentRuleProvider
import lin.repository.card_purpose.CardPurposeEntity
import lin.repository.card_purpose.CardPurposeRepository
import lin.rule.tree.EvaluatorTreeBindingType
import lin.ui.card_purpose.PurposeTagProvider
import lin.ui.service.TreeConfigService
import java.time.LocalDate

private sealed interface PurposeTagQuery {
    data object ListAction : PurposeTagQuery
    data class GetAction(val tagId: String) : PurposeTagQuery
}

private data class PurposeTagQueryInput(
    @field:JsonPropertyDescription("操作类型：LIST（列出所有用途标签摘要与匹配优先级），GET（读取特定用途标签的意图推导规则、关联卡牌与绑定该 Tag 的评估树）。有效值仅限：LIST, GET")
    val action: String,

    @field:JsonPropertyDescription("用途标签 ID（如 SAVE_LIFE, CLEAN, FINISH, GREED, VALUE, EXTRA_COST）。action=GET 时必填。")
    val tagId: String? = null
) {
    fun toQuery(): PurposeTagQuery = when (action.uppercase().trim()) {
        "LIST" -> PurposeTagQuery.ListAction
        "GET" -> {
            val tagId = tagId
            if (tagId.isNullOrBlank()) throw McpBadInput("action=GET 需要 tagId 参数") else PurposeTagQuery.GetAction(
                tagId
            )
        }

        else -> throw McpBadInput("不支持的 action: [$action]。有效值仅限：LIST, GET")
    }
}

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

/** 用途标签摘要 DTO (action=LIST) */
data class PurposeTagSummaryDto(
    val tagId: String,
    val displayName: String,
    @field:JsonPropertyDescription("默认映射的出牌阶段。可选值：RESOURCE, SETUP, CLEAR, DEFEND, COMBO, GENERAL, END")
    val defaultStage: String,
    val priority: Int,
    val associatedCardCount: Int
)

/** 用途标签详细信息 DTO (action=GET) */
data class PurposeTagDetailDto(
    val tagId: String,
    val displayName: String,
    @field:JsonPropertyDescription("默认映射的出牌阶段。可选值：RESOURCE, SETUP, CLEAR, DEFEND, COMBO, GENERAL, END")
    val defaultStage: String,
    val defaultOrderWeight: Double,
    val defaultReplanAfterUse: Boolean,
    val priority: Int,
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

/**
 * 用途标签 (PurposeTag) MCP 查询工具提供者。
 * 支持 action=LIST（列出标签及其出牌阶段规则摘要）与 action=GET（读取标签完整推导规则、关联卡牌及绑定树）。
 */
class PurposeTagToolProvider(
    private val tagProvider: PurposeTagProvider,
    private val ruleProvider: PurposeTagIntentRuleProvider,
    private val cardPurposeRepository: CardPurposeRepository,
    private val treeConfigService: TreeConfigService
) : McpToolProvider {

    override fun provide(): List<McpToolHandler> = listOf(
        typedTool<PurposeTagQueryInput>(
            name = "purpose_tag",
            description = """
                查询用途标签 (PurposeTag) 定义与意图推导规则。
                支持 action=LIST（列出系统已定义的全部用途标签及其默认出牌阶段和优先级摘要），
                以及 action=GET（读取特定 tagId 的意图规则细节、拥有该标签的卡牌清单及绑定该标签的评估树）。
            """.trimIndent()
        ) { input ->
            when (val query = input.toQuery()) {
                is PurposeTagQuery.ListAction -> handleList()
                is PurposeTagQuery.GetAction -> handleGet(query.tagId)
            }
        },

        // ── save_card_purpose: 批量打标（增删查合一，重复调用幂等）──
        typedTool<SaveCardPurposeInput>(
            name = "save_card_purpose",
            description = """
                批量设置卡牌的战略用途标签（PURPOSE_TAG 打标），并可调整 replanAfterUse。
                重复调用幂等：对同一卡牌以同一 tags 集合再次调用结果不变。

                purposeTags 为【完整覆盖】语义：传入后该卡最终标签 = 传入集合（与 UI 打标"合并 Tag"不同，此处直接整表替换），
                传空集合表示清除该卡全部标签。replanAfterUse 为 null 表示不修改当前值。

                典型场景：给过牌卡打 DRAW_CARD 标签，或给某卡增补/移除用途标签。
            """.trimIndent()
        ) { input ->
            handleSave(input)
        }
    )

    private fun handleSave(input: SaveCardPurposeInput): McpToolResult {
        if (input.cardIds.isEmpty()) throw McpBadInput("cardIds 不能为空")

        // 校验标签合法性：仅允许已定义标签，防幻觉打未知 tag
        val availableTagIds = tagProvider.tags().map { it.id.value }.toSet()
        val unknownTags = input.purposeTags.filter { it !in availableTagIds }
        if (unknownTags.isNotEmpty()) {
            throw McpBadInput("未知用途标签: $unknownTags。当前可用标签: $availableTagIds")
        }

        val now = LocalDate.now().toString()
        val entities = input.cardIds.distinct().map { cardId ->
            val existing = cardPurposeRepository.findByCardId(cardId)
            CardPurposeEntity(
                cardId = cardId,
                name = existing?.name,
                purposeTags = input.purposeTags.joinToString(","),
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

    private fun handleList(): McpToolResult {
        val tags = tagProvider.tags()
        val rules = ruleProvider.rules().associateBy { it.tagId.value }
        val allCardPurposes = cardPurposeRepository.findAll()

        // 预计算卡牌→标签集合，避免对每个 tag 反复 split
        val cardTagSets = allCardPurposes.map { entity ->
            entity.purposeTags.split(",").map { it.trim() }.toSet()
        }

        val summaries = tags.map { tagDef ->
            val tagIdStr = tagDef.id.value
            val rule = rules[tagIdStr]
            val cardCount = cardTagSets.count { it.contains(tagIdStr) }
            PurposeTagSummaryDto(
                tagId = tagIdStr,
                displayName = tagDef.displayName,
                defaultStage = rule?.defaultStage?.name ?: "GENERAL",
                priority = rule?.priority ?: 100,
                associatedCardCount = cardCount
            )
        }
        return mcpSuccess(summaries)
    }

    private fun handleGet(targetTagId: String): McpToolResult {
        val targetTagId = targetTagId.trim()
        val availableTags = tagProvider.tags()
        val availableTagIds = availableTags.map { it.id.value }

        if (targetTagId.isNullOrBlank() || targetTagId !in availableTagIds) {
            return mcpError("action=GET 必须提供合法的 tagId。当前可用标签: $availableTagIds")
        }

        val tagDef = availableTags.first { it.id.value == targetTagId }
        val rules = ruleProvider.rules().associateBy { it.tagId.value }
        val rule = rules[targetTagId]

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

        val detail = PurposeTagDetailDto(
            tagId = targetTagId,
            displayName = tagDef.displayName,
            defaultStage = rule?.defaultStage?.name ?: "GENERAL",
            defaultOrderWeight = rule?.defaultOrderWeight ?: 0.0,
            defaultReplanAfterUse = rule?.defaultReplanAfterUse ?: false,
            priority = rule?.priority ?: 100,
            associatedCards = associatedCards,
            boundEvaluatorTrees = boundTrees
        )
        return mcpSuccess(detail)
    }
}
