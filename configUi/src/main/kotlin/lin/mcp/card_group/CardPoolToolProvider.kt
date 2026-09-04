package lin.mcp.card_group

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import lin.ai.config.CardGroupQueryService
import lin.config.PathConfig
import lin.dao.CardGroupJsonParser
import lin.dao.CardWeightConfig
import lin.mcp.*
import lin.mcp.action.*
import lin.repository.HsCardRepository
import lin.repository.card_group.CardGroupService
import lin.serviceLoader.cardInfoProvide.decodeCostValue
import lin.serviceLoader.cardInfoProvide.encodeCostValue
import lin.utils.HearthstoneDeckCodeParser
import java.nio.file.Files

/**
 * 卡池域 MCP 工具提供者（写工具 + 动作同文件）：
 * - [CardPoolAction]：resource=card_pool 的 get/list/delete（原 card_pool 查询 + delete_card_pool 工具）。
 * - provide()：parse_hearthstone_deck_code / save_card_pool_weights 写工具。
 * 分组方案域见 [CardGroupToolProvider]。
 */
class CardPoolToolProvider(
    sourceService: CardGroupQueryService,
    groupService: CardGroupService,
    private val cardRepo: HsCardRepository
) : McpToolProvider {

    override val actions: List<ResourceAction> = listOf(
        CardPoolAction(sourceService, groupService)
    )

    override fun provide(): List<McpToolHandler> = listOf(
        // ── parse_hearthstone_deck_code ──
        typedTool<ParseDeckCodeInput>(
            name = "parse_hearthstone_deck_code",
            description = "解析炉石卡组代码（deck string）为卡牌列表（cardId/name/text/cost/type/attack/health/race/cardClass）。传 groupName 可把卡池直接写成 data/cardgroup/<groupName>.cardgroup 文件，供 get/list(resource=card_pool) / save_card_group 使用。返回的 parsedCards 已含完整游戏属性，无需再二次调用卡池详情探查费用/类型。"
        ) { input ->
            runCatching {
                val deck = HearthstoneDeckCodeParser.decode(input.deckCode)
                val cards = HearthstoneDeckCodeParser.parseToCards(input.deckCode, cardRepo)
                // 补全游戏属性（cost/type/attack/health/race/cardClass），避免调用方必须二次调卡池详情
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
            description = "为指定 .cardgroup 卡池文件更新或设置单卡的静态出牌权重 weight、开局换牌权重 changeWeight、配置等效费用 equivalentCost 与逐卡余费门槛 surplusIdleThreshold。等效费用 >0 时该卡基础价值按 costValue(等效费) 计算、覆盖身材/费用兜底，支持 0.5 档（如 3.5 = 等效 3.5 费）；逐卡余费门槛 N = 空闲 ≥ 牌费 + N 才肯垫出。更新时会保留原卡池中的其他卡牌，仅增量更新或追加传入单卡的权重配置；两语义字段 null = 保留该卡原值，0 = 清除对应声明。"
        ) { input ->
            if (input.fileName.isBlank()) return@typedTool mcpError("fileName 参数不能为空")
            if (input.cards.isEmpty()) return@typedTool mcpError("cards 列表不能为空")

            val existingConfig = CardGroupJsonParser.loadByFileName(input.fileName)
                ?: return@typedTool mcpError("卡池文件不存在: ${input.fileName}.cardgroup。请先用 parse_hearthstone_deck_code 创建卡池文件，或检查文件名是否正确。可用 list(resource=card_pool) 查看已有卡池。")
            // 以原卡池配置为基准保留原有卡牌，实现增量修补与更新
            val updatedCardMap = existingConfig.cards
                .associateBy { it.cardId }
                .toMutableMap()

            for (item in input.cards) {
                val cardId = item.cardId
                val oldItem = updatedCardMap[cardId]
                val cardName = item.name ?: oldItem?.name ?: cardRepo.findName(cardId) ?: cardId
                updatedCardMap[cardId] = CardWeightConfig(
                    cardId = cardId,
                    name = cardName,
                    weight = item.weight ?: oldItem?.weight,
                    changeWeight = item.changeWeight ?: oldItem?.changeWeight,
                    // powerWeight = 「等效费(≤1 位小数) + 门槛 N(百分位)」v4 单数编码（D-007，sop-rework T-002）：
                    // MCP 边界只收语义字段，编解码复用引擎单点（decodeCostValue/encodeCostValue），勿在本模块双实现。
                    powerWeight = mergePowerWeight(
                        oldRaw = oldItem?.powerWeight,
                        newEquivalentCost = item.equivalentCost,
                        newSurplusIdleThreshold = item.surplusIdleThreshold,
                        cardId = cardId
                    )
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
        }
    )

    // ── 动作：card_pool get/list/delete ──

    private class CardPoolAction(
        private val sourceService: CardGroupQueryService,
        private val groupService: CardGroupService
    ) : GetAction, ListAction, DeleteAction {

        override val resource: String = ActionResources.CARD_POOL

        override fun handleList(managerId: String?): McpToolResult {
            return mcpSuccess(sourceService.listCardGroupSources())
        }

        override fun handleGet(id: String): McpToolResult {
            val detail = sourceService.getCardGroupDetail(id)
                ?: return mcpError("卡池不存在: $id")
            return mcpSuccess(detail)
        }

        override val getFieldHint: String = "卡池文件名（不含 .cardgroup 后缀，由 list(resource=card_pool) 返回）"

        override fun handleDelete(id: String): McpToolResult {
            if (id.isBlank()) return mcpError("fileName 参数不能为空")
            val file = PathConfig.defaultDirPath.resolve("$id.cardgroup")
            if (!Files.exists(file)) return mcpError("卡池文件不存在: $id.cardgroup")

            // 检查依赖项：是否有 card_group 引用此卡池
            val dependents = groupService.loadAllManagers().filter { it.sourceFile == id }
            if (dependents.isNotEmpty()) {
                val depInfo = dependents.joinToString("\n") { mgr ->
                    "  - ${mgr.name} (id=${mgr.id})"
                }
                return mcpError(
                    "无法删除 $id.cardgroup，以下卡牌分组方案依赖此卡池:\n$depInfo\n" +
                            "请先删除这些方案（delete resource=card_group）或将其 sourceFile 改为其他卡池后重试。"
                )
            }

            Files.delete(file)
            return mcpSuccess(
                mapOf(
                    "deleted" to true,
                    "fileName" to id,
                    "filePath" to file.toString()
                )
            )
        }

        override val deleteFieldHint: String = "卡池文件名（不含 .cardgroup 后缀，由 list(resource=card_pool) 返回）"

        override val deleteSemantics: String = "删除前检查依赖：存在 card_group 引用此卡池时拒绝删除"
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
    @field:JsonPropertyDescription("需要更新权重的单卡列表。每张卡可指定 cardId, name, weight(静态出牌权重), changeWeight(开局换牌权重), equivalentCost(配置等效费用), surplusIdleThreshold(逐卡余费门槛)。未包含的既有卡牌将予以保留。")
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
    val changeWeight: Double? = null,
    @field:JsonPropertyDescription("可选：配置等效费用 equivalentCost（>0 时该卡基础价值 = costValue(等效费)，覆盖身材/费用兜底；支持 0.5 档，如 3.5 = 等效 3.5 费，须为 0.1 的整数倍）。传 0 = 清除等效费声明（还原身材/费用兜底）；null = 保留原值。")
    val equivalentCost: Double? = null,
    @field:JsonPropertyDescription("可选：逐卡余费门槛 surplusIdleThreshold（惜售语义：空闲 ≥ 牌费 + N 才肯垫出，垫后仍须剩 N 费；只影响余费垫牌，主搜索资格由候选策略/评估树决定）。取值 1~9；传 0 = 清除门槛（还原随时可垫）；null = 保留原值。分组级统一门槛用 save_card_group 的 binding.surplusIdleThreshold。")
    val surplusIdleThreshold: Int? = null
)

/**
 * save_card_pool_weights 单卡 powerWeight 的语义合并：显式提供的字段才生效，null = 保留旧值。
 * 返回编码后的 v4 单数（等效费 ≤1 位小数 + 门槛 N 占百分位）；null = 未配置（还原身材/费用兜底）。
 */
private fun mergePowerWeight(
    oldRaw: Double?,
    newEquivalentCost: Double?,
    newSurplusIdleThreshold: Int?,
    cardId: String
): Double? {
    if (newEquivalentCost == null && newSurplusIdleThreshold == null) return oldRaw
    val oldDecoded = decodeCostValue(oldRaw ?: 0.0)
    val eq = newEquivalentCost ?: oldDecoded.equivalentCostValue
    val n = when {
        newSurplusIdleThreshold == 0 -> null // 显式清除门槛（还原随时可垫）
        newSurplusIdleThreshold != null -> newSurplusIdleThreshold
        else -> oldDecoded.surplusIdleThreshold
    }
    return try {
        if (eq == 0.0 && n == null) null // 清除整个等效费声明
        else encodeCostValue(eq, n)
    } catch (e: IllegalArgumentException) {
        throw McpBadInput("cards[$cardId] 等效费用/门槛取值不合法: ${e.message}")
    }
}
