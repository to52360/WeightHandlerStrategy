package lin.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import lin.ai.config.CardGroupQueryService

/**
 * 卡池查询域 MCP 工具提供者。
 * 负责 .cardgroup 文件列表查询、卡池详情查询两个工具。
 * 新增卡池相关 tool 只改此文件。
 */
class CardGroupToolProvider(
    private val service: CardGroupQueryService,
    private val mapper: ObjectMapper
) : McpToolProvider {
    override fun provide(): List<McpToolHandler> = listOf(
        McpToolHandler(
            name = "list_card_group_sources",
            description = "列出 data/cardgroup/ 下所有 .cardgroup 卡组文件（文件名、启用状态、卡牌数量），让 AI 知道可基于哪些卡池进行分组编排。",
            inputSchemaJson = """{"type":"object","properties":{}}""",
            call = {
                McpToolResult(mapper.writeValueAsString(service.listCardGroupSources()))
            }
        ),
        McpToolHandler(
            name = "get_card_group_detail",
            description = "获取指定 .cardgroup 文件的完整卡池详情，每张卡包含 cardId、name、text（效果描述，可能为 null）。",
            inputSchemaJson = """
                {
                  "type": "object",
                  "required": ["fileName"],
                  "properties": {
                    "fileName": {
                      "type": "string",
                      "description": ".cardgroup 文件名（不含扩展名），由 list_card_group_sources 返回。"
                    }
                  }
                }
            """.trimIndent(),
            call = { args ->
                val fileName = args["fileName"] as? String
                if (fileName.isNullOrBlank()) {
                    McpToolResult(
                        mapper.writeValueAsString(mapOf("error" to "fileName is required")),
                        isError = true
                    )
                } else {
                    val detail = service.getCardGroupDetail(fileName)
                    if (detail == null) {
                        McpToolResult(
                            mapper.writeValueAsString(mapOf("error" to "card group not found: $fileName")),
                            isError = true
                        )
                    } else {
                        McpToolResult(mapper.writeValueAsString(detail))
                    }
                }
            }
        )
    )
}
