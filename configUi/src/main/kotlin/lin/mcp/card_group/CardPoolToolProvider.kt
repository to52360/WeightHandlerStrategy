package lin.mcp.card_group

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import lin.ai.config.CardGroupQueryService
import lin.config.PathConfig
import lin.dao.CardGroupJsonParser
import lin.dao.CardWeightConfig
import lin.mcp.*
import lin.repository.HsCardRepository
import lin.repository.card_group.CardGroupService
import lin.utils.HearthstoneDeckCodeParser
import java.nio.file.Files

/**
 * 卡池域 MCP 工具提供者（card_pool / parse_hearthstone_deck_code / save_card_pool_weights / delete_card_pool）。
 * 从原 CardGroupToolProvider 拆分（2026-08-10），按资源域隔离；分组方案域见 [CardGroupToolProvider]。
 */
class CardPoolToolProvider(
    private val sourceService: CardGroupQueryService,
    private val groupService: CardGroupService,
    private val cardRepo: HsCardRepository
) : McpToolProvider {

    override fun provide(): List<McpToolHandler> = listOf(
        // ── card_pool: 卡池文件列表 + 详情 (合并) ──
        typedTool<CardPoolInput>(
            name = "card_pool",
            description = "查询卡池文件。支持 action=LIST（列出所有 .cardgroup 文件摘要）和 action=GET（读取单个文件的完整卡牌详情：cardId/name/text/cost/type）。"
        ) { input ->
            when (val query = input.toQuery()) {
                is CardPoolQuery.ListAction -> mcpSuccess(sourceService.listCardGroupSources())
                is CardPoolQuery.GetAction -> {
                    val detail = sourceService.getCardGroupDetail(query.fileName)
                        ?: return@typedTool mcpError("卡池不存在: ${query.fileName}")
                    mcpSuccess(detail)
                }
            }
        },

        // ── parse_hearthstone_deck_code ──
        typedTool<ParseDeckCodeInput>(
            name = "parse_hearthstone_deck_code",
            description = "解析炉石卡组代码（deck string）为卡牌列表（cardId/name/text/cost/type/attack/health/race/cardClass）。传 groupName 可把卡池直接写成 data/cardgroup/<groupName>.cardgroup 文件，供 card_pool / save_card_group 使用。返回的 parsedCards 已含完整游戏属性，无需再二次调用 card_pool GET 探查费用/类型。"
        ) { input ->
            runCatching {
                val deck = HearthstoneDeckCodeParser.decode(input.deckCode)
                val cards = HearthstoneDeckCodeParser.parseToCards(input.deckCode, cardRepo)
                // 补全游戏属性（cost/type/attack/health/race/cardClass），避免调用方必须二次调 card_pool GET
                val detailMap = cardRepo.findCardDetailsByIds(cards.map { it.cardId }).associateBy { it.cardId }
                val savedFile = input.groupName?.takeIf { it.isNotBlank() }?.let { name ->
                    CardGroupJsonParser.saveCardGroup(cards, name, input.enabled ?: true).fileName.toString()
                }
                mcpSuccess(
                    mapOf(
                        "format" to deck.format, "heroes" to deck.heroes,
                        "totalCardsInCode" to deck.cards.size, "parsedCount" to cards.size,
                        "parsedCards" to cards.map { c ->
                            val d = detailMap[c.cardId]
                            mapOf(
                                "cardId" to c.cardId,
                                "name" to c.name,
                                "text" to c.text,
                                "cost" to d?.cost,
                                "type" to d?.type,
                                "attack" to d?.attack,
                                "health" to d?.health,
                                "race" to d?.race,
                                "cardClass" to d?.cardClass
                            )
                        },
                        "savedFile" to savedFile
                    )
                )
            }.getOrElse { e ->
                mcpError("解析失败: ${e.message}")
            }
        },

        // ── save_card_pool_weights (卡牌权重与换牌权重更新设置工具) ──
        typedTool<SaveCardPoolWeightsInput>(
            name = "save_card_pool_weights",
            description = "为指定 .cardgroup 卡池文件更新或设置单卡的静态出牌权重 weight 与开局换牌权重 changeWeight。更新时会保留原卡池中的其他卡牌，仅增量更新或追加传入单卡的权重配置。"
        ) { input ->
            if (input.fileName.isBlank()) return@typedTool mcpError("fileName 参数不能为空")
            if (input.cards.isEmpty()) return@typedTool mcpError("cards 列表不能为空")

            val existingConfig = CardGroupJsonParser.loadByFileName(input.fileName)
                ?: return@typedTool mcpError("卡池文件不存在: ${input.fileName}.cardgroup。请先用 parse_hearthstone_deck_code 创建卡池文件，或检查文件名是否正确。可用 card_pool(action=\"LIST\") 查看已有卡池。")
            // 以原卡池配置为基准保留原有卡牌，实现增量修补与更新
            val updatedCardMap = existingConfig.cards
                .associateBy { it.cardId }
                .toMutableMap()

            for ((cardId, name, weight, changeWeight) in input.cards) {
                val oldItem = updatedCardMap[cardId]
                val cardName = name ?: oldItem?.name ?: cardRepo.findName(cardId) ?: cardId
                updatedCardMap[cardId] = CardWeightConfig(
                    cardId = cardId,
                    name = cardName,
                    weight = weight ?: oldItem?.weight,
                    changeWeight = changeWeight ?: oldItem?.changeWeight
                )
            }

            val finalConfigs = updatedCardMap.values.toList()
            val savedPath = CardGroupJsonParser.saveCardGroupConfigs(
                configs = finalConfigs,
                groupName = input.fileName,
                enabled = input.enabled ?: existingConfig.enabled
            )
            mcpSuccess(
                mapOf(
                    "saved" to true,
                    "fileName" to input.fileName,
                    "savedPath" to savedPath.toString(),
                    "totalCardCount" to finalConfigs.size,
                    "updatedCardCount" to input.cards.size
                )
            )
        },

        // ── delete_card_pool (卡池文件删除) ──
        typedTool<DeleteCardPoolInput>(
            name = "delete_card_pool",
            description = "删除一个 .cardgroup 卡池文件。删除前会检查依赖项：如果存在任何卡牌分组方案（card_group）引用此卡池文件，则拒绝删除并列出所有依赖方。"
        ) { input ->
            if (input.fileName.isBlank()) return@typedTool mcpError("fileName 参数不能为空")
            val file = PathConfig.defaultDirPath.resolve("${input.fileName}.cardgroup")
            if (!Files.exists(file)) return@typedTool mcpError("卡池文件不存在: ${input.fileName}.cardgroup")

            // 检查依赖项：是否有 card_group 引用此卡池
            val dependents = groupService.loadAllManagers().filter { it.sourceFile == input.fileName }
            if (dependents.isNotEmpty()) {
                val depInfo = dependents.joinToString("\n") { mgr ->
                    "  - ${mgr.name} (id=${mgr.id})"
                }
                return@typedTool mcpError(
                    "无法删除 ${input.fileName}.cardgroup，以下卡牌分组方案依赖此卡池:\n$depInfo\n" +
                            "请先删除这些方案（delete_card_group）或将其 sourceFile 改为其他卡池后重试。"
                )
            }

            Files.delete(file)
            mcpSuccess(
                mapOf(
                    "deleted" to true,
                    "fileName" to input.fileName,
                    "filePath" to file.toString()
                )
            )
        }
    )
}

private sealed interface CardPoolQuery {
    data object ListAction : CardPoolQuery
    data class GetAction(val fileName: String) : CardPoolQuery
}

private data class CardPoolInput(
    @field:JsonPropertyDescription("操作类型：LIST 列出所有卡池文件摘要，GET 读取单个文件详情（需传 fileName）")
    val action: String,
    @field:JsonPropertyDescription("卡池文件名（不含扩展名），仅 action=GET 时需要。")
    val fileName: String? = null
) {
    fun toQuery(): CardPoolQuery = when (action.uppercase()) {
        "GET" -> {
            val fileName = fileName
            if (fileName.isNullOrBlank()) throw McpBadInput("action=GET 需要 fileName 参数") else CardPoolQuery.GetAction(
                fileName
            )
        }

        "LIST" -> CardPoolQuery.ListAction
        else -> throw McpBadInput("未知 action: $action，支持 LIST / GET")
    }
}

private data class ParseDeckCodeInput(
    @field:JsonPropertyDescription("卡组代码（deck string，如 AAEBAZfDAwaopAOX7wS2xAXH...）")
    val deckCode: String,
    @field:JsonPropertyDescription("若提供，会把解析出的卡池写入 data/cardgroup/<groupName>.cardgroup；不提供则仅返回解析结果。")
    val groupName: String? = null,
    @field:JsonPropertyDescription("写入文件时是否启用，默认 true。")
    val enabled: Boolean? = true
)

private data class SaveCardPoolWeightsInput(
    @field:JsonPropertyDescription("卡池文件名（不含 .cardgroup 后缀），如 real_libram_deck")
    val fileName: String,
    @field:JsonPropertyDescription("需要更新权重的单卡列表。每张卡可指定 cardId, name, weight(静态出牌权重), changeWeight(开局换牌权重)。未包含的既有卡牌将予以保留。")
    val cards: List<CardWeightItemInput>,
    @field:JsonPropertyDescription("文件是否启用，缺省保持原文件状态或默认 true")
    val enabled: Boolean? = null
)

private data class CardWeightItemInput(
    @field:JsonPropertyDescription("卡牌 ID，如 BT_020")
    val cardId: String,
    @field:JsonPropertyDescription("卡牌名称（可选）")
    val name: String? = null,
    @field:JsonPropertyDescription("静态出牌权重 weight（可选）")
    val weight: Double? = null,
    @field:JsonPropertyDescription("开局换牌权重 changeWeight（可选，正数偏好保留，负数偏好换掉，如 15.0 或 -100.0）")
    val changeWeight: Double? = null
)

private data class DeleteCardPoolInput(
    @field:JsonPropertyDescription("要删除的卡池文件名（不含 .cardgroup 后缀），由 card_pool(action=LIST) 获取。")
    val fileName: String
)
