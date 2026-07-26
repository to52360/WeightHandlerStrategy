package lin.mcp

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import lin.bean.usePlan.PurposeTagIntentRuleProvider
import lin.repository.card_purpose.CardPurposeRepository
import lin.rule.tree.EvaluatorTreeBindingType
import lin.ui.card_purpose.PurposeTagProvider
import lin.ui.service.TreeConfigService

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
        }
    )

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
