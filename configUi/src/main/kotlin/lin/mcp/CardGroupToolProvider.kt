package lin.mcp

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.fasterxml.jackson.databind.ObjectMapper
import lin.ai.config.CardGroupQueryService
import lin.dao.CardGroupJsonParser
import lin.db.HsCardRepository
import lin.rule.tree.CardGroupBinding
import lin.ui.card_group.db.CardGroupService
import lin.utils.HearthstoneDeckCodeParser
import lin.utils.nextShortId

/**
 * 卡池与分组查询域 MCP 工具提供者。
 * 负责 .cardgroup 文件查询 + DB 分组方案查询与保存。
 * 新增卡池/分组相关 tool 只改此文件。
 */
class CardGroupToolProvider(
    private val sourceService: CardGroupQueryService,
    private val groupService: CardGroupService,
    private val cardRepo: HsCardRepository,
    private val mapper: ObjectMapper
) : McpToolProvider {
    override fun provide(): List<McpToolHandler> = listOf(
        McpToolHandler(
            name = "list_card_group_sources",
            description = "列出所有可用 .cardgroup 卡组文件（文件名、启用状态、卡牌数量），让 AI 知道可基于哪些卡池进行分组编排。",
            inputSchemaJson = """{"type":"object","properties":{}}""",
            call = {
                McpToolResult(mapper.writeValueAsString(sourceService.listCardGroupSources()))
            }
        ),
        typedTool<GetCardGroupDetailInput>(
            name = "get_card_group_detail",
            description = "获取指定 .cardgroup 文件的完整卡池详情，每张卡包含 cardId、name、text（效果描述，可能为 null）。",
            mapper = mapper
        ) { input ->
            if (input.fileName.isBlank()) {
                McpToolResult(
                    mapper.writeValueAsString(mapOf("error" to "fileName is required")),
                    isError = true
                )
            } else {
                val detail = sourceService.getCardGroupDetail(input.fileName)
                if (detail == null) {
                    McpToolResult(
                        mapper.writeValueAsString(mapOf("error" to "card group not found: ${input.fileName}")),
                        isError = true
                    )
                } else {
                    McpToolResult(mapper.writeValueAsString(detail))
                }
            }
        },
        McpToolHandler(
            name = "list_card_groups",
            description = "查询已有的卡牌分组方案（Manager）摘要列表，包含 id、name、sourceFile、enabled。用于绑定评估树时引用分组。各方案下的具体绑定条目（含 binding id，即 create_draft_tree 的 bindingIds 所需）由 get_card_group_manager 提供。",
            inputSchemaJson = """{"type":"object","properties":{}}""",
            call = {
                McpToolResult(mapper.writeValueAsString(groupService.loadAllManagers()))
            }
        ),
        typedTool<GetCardGroupManagerInput>(
            name = "get_card_group_manager",
            description = "查询某个卡牌分组方案（Manager）的完整信息，包括其下的绑定条目列表。每个 binding 含 id（即 create_draft_tree 的 bindingIds 所需的绑定条目 ID）、name、description、cardIds。managerId 来自 list_card_groups 返回的 id。",
            mapper = mapper
        ) { input ->
            val manager = groupService.loadAllManagers().firstOrNull { it.id == input.managerId }
            if (manager == null) {
                McpToolResult(
                    mapper.writeValueAsString(mapOf("error" to "manager not found: ${input.managerId}")),
                    isError = true
                )
            } else {
                val bindings = groupService.loadBindings(input.managerId).map { b ->
                    mapOf(
                        "id" to b.id,
                        "name" to b.name,
                        "description" to b.description,
                        "cardIds" to b.cardIds
                    )
                }
                val response = mapOf(
                    "id" to manager.id,
                    "name" to manager.name,
                    "sourceFile" to manager.sourceFile,
                    "enabled" to manager.enabled,
                    "bindings" to bindings
                )
                McpToolResult(mapper.writeValueAsString(response))
            }
        },
        typedTool<SaveCardGroupInput>(
            name = "save_card_group",
            description = "创建或更新卡牌分组方案。existingId 非空时更新已有方案；为空时新建。bindings 中 name 为分组名、description 为说明、cardIds 必须来自 sourceFile 对应卡池。",
            mapper = mapper
        ) { input ->
            // 校验卡池文件存在
            val cardPool = CardGroupJsonParser.loadByFileName(input.sourceFile)
                ?: return@typedTool McpToolResult(
                    mapper.writeValueAsString(mapOf("error" to "sourceFile not found: ${input.sourceFile}")),
                    isError = true
                )
            val validCardIds = cardPool.cards.map { it.cardId }.toSet()

            // 校验 cardIds
            input.bindings.forEachIndexed { i, bi ->
                val invalidIds = bi.cardIds.filter { it !in validCardIds }
                if (invalidIds.isNotEmpty()) {
                    return@typedTool McpToolResult(
                        mapper.writeValueAsString(
                            mapOf(
                                "error" to "bindings[$i] contains cardIds not in sourceFile '${input.sourceFile}': $invalidIds"
                            )
                        ),
                        isError = true
                    )
                }
            }

            val managerName = input.managerName?.takeIf { it.isNotBlank() } ?: input.sourceFile
            val existingId = input.existingId?.takeIf { it.isNotBlank() }

            val bindings = input.bindings.map { bi ->
                CardGroupBinding(
                    id = nextShortId(),
                    managerId = "", // 由 saveManager 内赋值
                    name = bi.name,
                    cardIds = bi.cardIds,
                    description = bi.description
                )
            }

            val managerId = groupService.saveManager(
                name = managerName,
                sourceFile = input.sourceFile,
                enabled = true,
                bindings = bindings,
                existingId = existingId
            )

            McpToolResult(
                mapper.writeValueAsString(
                mapOf(
                "managerId" to managerId,
                "managerName" to managerName,
                "bindingIds" to bindings.map { it.id }
            )))
        },
        typedTool<ParseDeckCodeInput>(
            name = "parse_hearthstone_deck_code",
            description = "解析炉石卡组代码（deck string）为卡牌列表（cardId/名称/效果）。传 groupName 可把卡池直接写成 data/cardgroup/<groupName>.cardgroup 文件，供 list_card_group_sources / save_card_group 使用。返回字段说明：heroes 为英雄的 dbfId 列表（需自行映射职业，如 671=死亡骑士）；totalCardsInCode 是卡组代码中「去重后的卡牌种类数」（非整卡张数，标准卡组 30 张但种类通常不到 30）；parsedCount 是实际在 hs_cards.db 中命中的去重卡数（缺卡不会出现在 parsedCards 里）。",
            mapper = mapper
        ) { input ->
            runCatching {
                val deck = HearthstoneDeckCodeParser.decode(input.deckCode)
                val cards = HearthstoneDeckCodeParser.parseToCards(input.deckCode, cardRepo)
                val savedFile = input.groupName?.takeIf { it.isNotBlank() }?.let { name ->
                    CardGroupJsonParser.saveCardGroup(cards, name, input.enabled ?: true).fileName.toString()
                }
                val response = mapOf(
                    "format" to deck.format,
                    "heroes" to deck.heroes,
                    "totalCardsInCode" to deck.cards.size,
                    "parsedCount" to cards.size,
                    "parsedCards" to cards.map {
                        mapOf("cardId" to it.cardId, "name" to it.name, "text" to it.text)
                    },
                    "savedFile" to savedFile
                )
                McpToolResult(mapper.writeValueAsString(response))
            }.getOrElse { e ->
                McpToolResult(
                    mapper.writeValueAsString(mapOf("error" to "解析失败: ${e.message}")),
                    isError = true
                )
            }
        }
    )
}

private data class GetCardGroupDetailInput(
    @field:JsonPropertyDescription(".cardgroup 文件名（不含扩展名），由 list_card_group_sources 返回。")
    val fileName: String
)

private data class SaveCardGroupInput(
    @field:JsonPropertyDescription(".cardgroup 文件名（不含扩展名），卡池来源。")
    val sourceFile: String,
    @field:JsonPropertyDescription("方案名称，缺省使用 sourceFile。")
    val managerName: String? = null,
    @field:JsonPropertyDescription("已有方案的 id（来自 list_card_groups），非空则更新该方案。")
    val existingId: String? = null,
    @field:JsonPropertyDescription("分组列表")
    val bindings: List<SaveCardGroupBindingInput> = emptyList()
)

private data class SaveCardGroupBindingInput(
    @field:JsonPropertyDescription("分组名称")
    val name: String,
    @field:JsonPropertyDescription("该分组包含的卡牌 ID 列表")
    val cardIds: List<String>,
    @field:JsonPropertyDescription("分组说明，用于绑定评估树时理解分组用途")
    val description: String? = null
)

private data class ParseDeckCodeInput(
    @field:JsonPropertyDescription("卡组代码（deck string，如 AAEBAZfDAwaopAOX7wS2xAXH...）")
    val deckCode: String,
    @field:JsonPropertyDescription("若提供，会把解析出的卡池写入 data/cardgroup/ 下名为 <groupName>.cardgroup 的文件；不提供则仅返回解析结果。")
    val groupName: String? = null,
    @field:JsonPropertyDescription("写入文件时是否启用（仅当 groupName 提供时生效），默认 true。")
    val enabled: Boolean? = true
)

private data class GetCardGroupManagerInput(
    @field:JsonPropertyDescription("要查询的卡组方案（Manager）ID，来自 list_card_groups 返回的 id")
    val managerId: String
)
