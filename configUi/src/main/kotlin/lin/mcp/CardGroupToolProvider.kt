package lin.mcp

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.fasterxml.jackson.databind.ObjectMapper
import lin.ai.config.CardGroupQueryService
import lin.dao.CardGroupJsonParser
import lin.rule.tree.CardGroupBinding
import lin.ui.card_group.db.CardGroupService
import lin.utils.nextShortId

/**
 * 卡池与分组查询域 MCP 工具提供者。
 * 负责 .cardgroup 文件查询 + DB 分组方案查询与保存。
 * 新增卡池/分组相关 tool 只改此文件。
 */
class CardGroupToolProvider(
    private val sourceService: CardGroupQueryService,
    private val groupService: CardGroupService,
    private val mapper: ObjectMapper
) : McpToolProvider {
    override fun provide(): List<McpToolHandler> = listOf(
        McpToolHandler(
            name = "list_card_group_sources",
            description = "列出 data/cardgroup/ 下所有 .cardgroup 卡组文件（文件名、启用状态、卡牌数量），让 AI 知道可基于哪些卡池进行分组编排。",
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
            description = "查询已有的卡牌分组方案（Manager）摘要列表，包含 id、name、sourceFile、enabled。用于绑定评估树时引用分组。详细绑定内容（cardIds、overrides）由后续工具提供。",
            inputSchemaJson = """{"type":"object","properties":{}}""",
            call = {
                McpToolResult(mapper.writeValueAsString(groupService.loadAllManagers()))
            }
        ),
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
