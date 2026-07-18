package lin.mcp

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.fasterxml.jackson.databind.ObjectMapper
import lin.ai.config.CardGroupQueryService
import lin.dao.CardGroupJsonParser
import lin.db.HsCardRepository
import lin.rule.tree.CardGroupBinding
import lin.ui.card_group.db.CardGroupService
import lin.ui.service.TreeConfigService
import lin.utils.HearthstoneDeckCodeParser
import lin.utils.nextShortId

/**
 * 卡池与分组域 MCP 工具提供者。
 * 负责 .cardgroup 文件查询 + DB 分组方案增删查。
 * 新增卡池/分组相关 tool 只改此文件。
 */
class CardGroupToolProvider(
    private val sourceService: CardGroupQueryService,
    private val groupService: CardGroupService,
    private val cardRepo: HsCardRepository,
    private val treeConfigService: TreeConfigService,
    private val mapper: ObjectMapper
) : McpToolProvider {
    override fun provide(): List<McpToolHandler> = listOf(
        // ── card_pool: 卡池文件列表 + 详情 (合并) ──
        typedTool<CardPoolInput>(
            name = "card_pool",
            description = "查询卡池文件。支持 action=LIST（列出所有 .cardgroup 文件摘要）和 action=GET（读取单个文件的完整卡牌详情：cardId/name/text/cost/type）。",
            mapper = mapper
        ) { input ->
            when (input.action.uppercase()) {
                "LIST" -> McpToolResult(mapper.writeValueAsString(sourceService.listCardGroupSources()))
                "GET" -> {
                    if (input.fileName.isNullOrBlank()) {
                        McpToolResult(mapper.writeValueAsString(mapOf("error" to "action=GET 需要 fileName 参数")), isError = true)
                    } else {
                        val detail = sourceService.getCardGroupDetail(input.fileName)
                        if (detail == null) {
                            McpToolResult(mapper.writeValueAsString(mapOf("error" to "卡池不存在: ${input.fileName}")), isError = true)
                        } else {
                            McpToolResult(mapper.writeValueAsString(detail))
                        }
                    }
                }
                else -> McpToolResult(mapper.writeValueAsString(mapOf("error" to "未知 action: ${input.action}，支持 LIST / GET")), isError = true)
            }
        },

        // ── card_group: 分组方案列表 + 详情 (合并) ──
        typedTool<CardGroupInput>(
            name = "card_group",
            description = "查询卡牌分组方案。支持 action=LIST（列出所有方案的 id/name/sourceFile/enabled 摘要）和 action=GET（读取某个方案的完整信息，含所有 binding 条目的 id/name/cardIds）。",
            mapper = mapper
        ) { input ->
            when (input.action.uppercase()) {
                "LIST" -> McpToolResult(mapper.writeValueAsString(groupService.loadAllManagers()))
                "GET" -> {
                    if (input.managerId.isNullOrBlank()) {
                        McpToolResult(mapper.writeValueAsString(mapOf("error" to "action=GET 需要 managerId 参数")), isError = true)
                    } else {
                        val manager = groupService.loadAllManagers().firstOrNull { it.id == input.managerId }
                        if (manager == null) {
                            McpToolResult(mapper.writeValueAsString(mapOf("error" to "方案不存在: ${input.managerId}")), isError = true)
                        } else {
                            val bindings = groupService.loadBindings(input.managerId).map { b ->
                                mapOf("id" to b.id, "name" to b.name, "description" to b.description, "cardIds" to b.cardIds)
                            }
                            McpToolResult(mapper.writeValueAsString(mapOf(
                                "id" to manager.id, "name" to manager.name,
                                "sourceFile" to manager.sourceFile, "enabled" to manager.enabled,
                                "bindings" to bindings
                            )))
                        }
                    }
                }
                else -> McpToolResult(mapper.writeValueAsString(mapOf("error" to "未知 action: ${input.action}，支持 LIST / GET")), isError = true)
            }
        },

        // ── save_card_group (保留) ──
        typedTool<SaveCardGroupInput>(
            name = "save_card_group",
            description = "创建或更新卡牌分组方案。existingId 非空时更新已有方案；为空时新建。bindings 中 name 为分组名、cardIds 必须来自 sourceFile 对应卡池。方案 id 由 card_group(action=LIST) 获取。",
            mapper = mapper
        ) { input ->
            val cardPool = CardGroupJsonParser.loadByFileName(input.sourceFile)
                ?: return@typedTool McpToolResult(
                    mapper.writeValueAsString(mapOf("error" to "sourceFile not found: ${input.sourceFile}")),
                    isError = true
                )
            val validCardIds = cardPool.cards.map { it.cardId }.toSet()
            input.bindings.forEachIndexed { i, bi ->
                val invalidIds = bi.cardIds.filter { it !in validCardIds }
                if (invalidIds.isNotEmpty()) {
                    return@typedTool McpToolResult(
                        mapper.writeValueAsString(mapOf("error" to "bindings[$i] contains cardIds not in sourceFile '${input.sourceFile}': $invalidIds")),
                        isError = true
                    )
                }
            }
            val managerName = input.managerName?.takeIf { it.isNotBlank() } ?: input.sourceFile
            val existingId = input.existingId?.takeIf { it.isNotBlank() }
            val bindings = input.bindings.map { bi ->
                CardGroupBinding(id = nextShortId(), managerId = "", name = bi.name, cardIds = bi.cardIds, description = bi.description)
            }
            val managerId = groupService.saveManager(name = managerName, sourceFile = input.sourceFile, enabled = true, bindings = bindings, existingId = existingId)
            McpToolResult(mapper.writeValueAsString(mapOf("managerId" to managerId, "managerName" to managerName, "bindingIds" to bindings.map { it.id })))
        },

        // ── delete_card_group (保留) ──
        typedTool<DeleteCardGroupInput>(
            name = "delete_card_group",
            description = "删除一个卡牌分组方案及其所有绑定条目和关联的评估树。managerId 由 card_group(action=LIST) 获取。删除不可恢复，返回被删内容清单。",
            mapper = mapper
        ) { input ->
            val allManagers = groupService.loadAllManagers()
            val manager = allManagers.firstOrNull { it.id == input.managerId }
                ?: return@typedTool McpToolResult(mapper.writeValueAsString(mapOf("error" to "方案不存在: ${input.managerId}")), isError = true)
            val bindings = groupService.loadBindings(input.managerId)
            val bindingNames = bindings.map { it.name }
            val allTreeSummaries = treeConfigService.loadSummaries()
            val linkedTrees = allTreeSummaries.filter { it["managerId"] == input.managerId }
            val treeNames = linkedTrees.map { it["name"] as? String ?: "" }
            linkedTrees.forEach { tree -> treeConfigService.delete(tree["id"] as String) }
            groupService.deleteManager(input.managerId)
            McpToolResult(mapper.writeValueAsString(mapOf(
                "deleted" to true, "managerId" to input.managerId, "managerName" to manager.name,
                "deletedBindings" to bindingNames, "deletedTrees" to treeNames,
                "totalDeleted" to (1 + bindings.size + linkedTrees.size)
            )))
        },

        // ── parse_hearthstone_deck_code (保留) ──
        typedTool<ParseDeckCodeInput>(
            name = "parse_hearthstone_deck_code",
            description = "解析炉石卡组代码（deck string）为卡牌列表（cardId/名称/效果）。传 groupName 可把卡池直接写成 data/cardgroup/<groupName>.cardgroup 文件，供 card_pool / save_card_group 使用。",
            mapper = mapper
        ) { input ->
            runCatching {
                val deck = HearthstoneDeckCodeParser.decode(input.deckCode)
                val cards = HearthstoneDeckCodeParser.parseToCards(input.deckCode, cardRepo)
                val savedFile = input.groupName?.takeIf { it.isNotBlank() }?.let { name ->
                    CardGroupJsonParser.saveCardGroup(cards, name, input.enabled ?: true).fileName.toString()
                }
                McpToolResult(mapper.writeValueAsString(mapOf(
                    "format" to deck.format, "heroes" to deck.heroes,
                    "totalCardsInCode" to deck.cards.size, "parsedCount" to cards.size,
                    "parsedCards" to cards.map { mapOf("cardId" to it.cardId, "name" to it.name, "text" to it.text) },
                    "savedFile" to savedFile
                )))
            }.getOrElse { e ->
                McpToolResult(mapper.writeValueAsString(mapOf("error" to "解析失败: ${e.message}")), isError = true)
            }
        }
    )
}

// ── 合并后的 input 数据类 ──

private data class CardPoolInput(
    @field:JsonPropertyDescription("操作类型：LIST 列出所有卡池文件摘要，GET 读取单个文件详情（需传 fileName）")
    val action: String,
    @field:JsonPropertyDescription("卡池文件名（不含扩展名），仅 action=GET 时需要。")
    val fileName: String? = null
)

private data class CardGroupInput(
    @field:JsonPropertyDescription("操作类型：LIST 列出所有方案摘要，GET 读取方案详情（需传 managerId）")
    val action: String,
    @field:JsonPropertyDescription("方案 id，仅 action=GET 时需要，由 card_group(action=LIST) 返回。")
    val managerId: String? = null
)

// ── 保留的 input 数据类 ──

private data class SaveCardGroupInput(
    @field:JsonPropertyDescription(".cardgroup 文件名（不含扩展名），卡池来源。")
    val sourceFile: String,
    @field:JsonPropertyDescription("方案名称，缺省使用 sourceFile。")
    val managerName: String? = null,
    @field:JsonPropertyDescription("已有方案的 id（由 card_group(action=LIST) 获取），非空则更新该方案。")
    val existingId: String? = null,
    @field:JsonPropertyDescription("分组列表")
    val bindings: List<SaveCardGroupBindingInput> = emptyList()
)

private data class SaveCardGroupBindingInput(
    @field:JsonPropertyDescription("分组名称")
    val name: String,
    @field:JsonPropertyDescription("该分组包含的卡牌 ID 列表")
    val cardIds: List<String>,
    @field:JsonPropertyDescription("分组说明")
    val description: String? = null
)

private data class ParseDeckCodeInput(
    @field:JsonPropertyDescription("卡组代码（deck string，如 AAEBAZfDAwaopAOX7wS2xAXH...）")
    val deckCode: String,
    @field:JsonPropertyDescription("若提供，会把解析出的卡池写入 data/cardgroup/<groupName>.cardgroup；不提供则仅返回解析结果。")
    val groupName: String? = null,
    @field:JsonPropertyDescription("写入文件时是否启用，默认 true。")
    val enabled: Boolean? = true
)

private data class DeleteCardGroupInput(
    @field:JsonPropertyDescription("要删除的方案 id，由 card_group(action=LIST) 获取。删除不可恢复。")
    val managerId: String
)
