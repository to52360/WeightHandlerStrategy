package lin.mcp

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import lin.ai.config.CardGroupQueryService
import lin.config.PathConfig
import lin.dao.CardGroupJsonParser
import lin.dao.CardWeightConfig
import lin.repository.HsCardRepository
import lin.repository.card_group.CardGroupService
import lin.rule.tree.CardGroupBinding
import lin.ui.service.TreeConfigService
import lin.utils.HearthstoneDeckCodeParser
import lin.utils.nextShortId
import java.nio.file.Files

/**
 * 卡池与分组域 MCP 工具提供者。
 * 负责 .cardgroup 文件查询 + DB 分组方案增删查。
 * 新增卡池/分组相关 tool 只改此文件。
 */
class CardGroupToolProvider(
    private val sourceService: CardGroupQueryService,
    private val groupService: CardGroupService,
    private val cardRepo: HsCardRepository,
    private val treeConfigService: TreeConfigService
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

        // ── card_group: 分组方案列表 + 详情 (合并) ──
        typedTool<CardGroupInput>(
            name = "card_group",
            description = "查询卡牌分组方案。支持 action=LIST（列出所有方案的 id/name/sourceFile/enabled 摘要）和 action=GET（读取某个方案的完整信息，含所有 binding 条目的 id/name/cardIds）。"
        ) { input ->
            when (val query = input.toQuery()) {
                is CardGroupQuery.ListAction -> mcpSuccess(groupService.loadAllManagers())
                is CardGroupQuery.GetAction -> {
                    val manager = groupService.loadAllManagers().firstOrNull { it.id == query.managerId }
                        ?: return@typedTool mcpError("方案不存在: ${query.managerId}")
                    val bindings = groupService.loadBindings(query.managerId).map { b ->
                        mapOf(
                            "id" to b.id,
                            "name" to b.name,
                            "description" to b.description,
                            "cardIds" to b.cardIds
                        )
                    }
                    mcpSuccess(
                        mapOf(
                            "id" to manager.id, "name" to manager.name,
                            "sourceFile" to manager.sourceFile, "enabled" to manager.enabled,
                            "bindings" to bindings
                        )
                    )
                }
            }
        },

        // ── save_card_group (保留) ──
        typedTool<SaveCardGroupInput>(
            name = "save_card_group",
            description = """创建或更新卡牌分组方案。
- 正常模式（创建/更新）：提供 sourceFile + bindings；existingId 非空时更新该方案，为空则新建
- 克隆模式：提供 cloneFrom，以该方案为蓝本创建副本，含所有 binding 与 behavior，managerName 缺省自动加「副本」后缀

两种模式互斥。bindings 中 name 为分组名、cardIds 必须来自 sourceFile 对应卡池。方案 id 由 card_group(action=LIST) 获取。"""
        ) { input ->
            // ── clone mode ──
            if (input.cloneFrom != null) {
                val sourceManager = groupService.loadAllManagers().firstOrNull { it.id == input.cloneFrom }
                    ?: return@typedTool mcpError("要克隆的方案不存在: ${input.cloneFrom}")
                val sourceBindings = groupService.loadBindings(input.cloneFrom)
                val effectiveSourceFile = input.sourceFile?.takeIf { it.isNotBlank() } ?: sourceManager.sourceFile
                val managerName = input.managerName?.takeIf { it.isNotBlank() }
                    ?: "${sourceManager.name} 副本"

                val clonedBindings = sourceBindings.map { b ->
                    CardGroupBinding(
                        id = nextShortId(),
                        managerId = "",
                        name = b.name,
                        cardIds = b.cardIds,
                        description = b.description,
                        behaviors = b.behaviors
                    )
                }

                val managerId = groupService.saveManager(
                    name = managerName,
                    sourceFile = effectiveSourceFile,
                    enabled = true,
                    bindings = clonedBindings,
                    existingId = null
                )
                return@typedTool mcpSuccess(
                    mapOf(
                        "managerId" to managerId,
                        "managerName" to managerName,
                        "bindingIds" to clonedBindings.map { it.id },
                        "clonedFrom" to input.cloneFrom
                    )
                )
            }

            // ── original create/update logic ──
            val sourceFile = input.sourceFile ?: return@typedTool mcpError("sourceFile 不能为空")
            if (input.bindings.isEmpty()) return@typedTool mcpError("bindings 不能为空，至少需要一个分组绑定条目")
            val cardPool = CardGroupJsonParser.loadByFileName(sourceFile)
                ?: return@typedTool mcpError("sourceFile not found: $sourceFile")
            val validCardIds = cardPool.cards.map { it.cardId }.toSet()
            input.bindings.forEachIndexed { i, bi ->
                val invalidIds = bi.cardIds.filter { it !in validCardIds }
                if (invalidIds.isNotEmpty()) {
                    return@typedTool mcpError("bindings[$i] contains cardIds not in sourceFile '$sourceFile': $invalidIds")
                }
            }
            val managerName = input.managerName?.takeIf { it.isNotBlank() } ?: sourceFile
            val existingId = input.existingId?.takeIf { it.isNotBlank() }
            val bindings = input.bindings.map { bi ->
                CardGroupBinding(
                    id = nextShortId(),
                    managerId = "",
                    name = bi.name,
                    cardIds = bi.cardIds,
                    description = bi.description
                )
            }
            val managerId = groupService.saveManager(
                name = managerName,
                sourceFile = sourceFile,
                enabled = true,
                bindings = bindings,
                existingId = existingId
            )
            mcpSuccess(
                mapOf(
                    "managerId" to managerId,
                    "managerName" to managerName,
                    "bindingIds" to bindings.map { it.id }
                )
            )
        },

        // ── delete_card_group (保留) ──
        typedTool<DeleteCardGroupInput>(
            name = "delete_card_group",
            description = "删除一个卡牌分组方案及其所有绑定条目和关联的评估树。managerId 由 card_group(action=LIST) 获取。删除不可恢复，返回被删内容清单。"
        ) { input ->
            val allManagers = groupService.loadAllManagers()
            val manager = allManagers.firstOrNull { it.id == input.managerId }
                ?: return@typedTool mcpError("方案不存在: ${input.managerId}")
            val bindings = groupService.loadBindings(input.managerId)
            val bindingNames = bindings.map { it.name }
            val allTreeSummaries = treeConfigService.loadSummaries()
            val linkedTrees = allTreeSummaries.filter { it["managerId"] == input.managerId }
            val treeNames = linkedTrees.map { it["name"] as? String ?: "" }
            linkedTrees.forEach { tree -> treeConfigService.delete(tree["id"] as String) }
            groupService.deleteManager(input.managerId)
            mcpSuccess(
                mapOf(
                    "deleted" to true, "managerId" to input.managerId, "managerName" to manager.name,
                    "deletedBindings" to bindingNames, "deletedTrees" to treeNames,
                    "totalDeleted" to (1 + bindings.size + linkedTrees.size)
                )
            )
        },

        // ── parse_hearthstone_deck_code (保留) ──
        typedTool<ParseDeckCodeInput>(
            name = "parse_hearthstone_deck_code",
            description = "解析炉石卡组代码（deck string）为卡牌列表（cardId/名称/效果）。传 groupName 可把卡池直接写成 data/cardgroup/<groupName>.cardgroup 文件，供 card_pool / save_card_group 使用。"
        ) { input ->
            runCatching {
                val deck = HearthstoneDeckCodeParser.decode(input.deckCode)
                val cards = HearthstoneDeckCodeParser.parseToCards(input.deckCode, cardRepo)
                val savedFile = input.groupName?.takeIf { it.isNotBlank() }?.let { name ->
                    CardGroupJsonParser.saveCardGroup(cards, name, input.enabled ?: true).fileName.toString()
                }
                mcpSuccess(
                    mapOf(
                        "format" to deck.format, "heroes" to deck.heroes,
                        "totalCardsInCode" to deck.cards.size, "parsedCount" to cards.size,
                        "parsedCards" to cards.map {
                            mapOf(
                                "cardId" to it.cardId,
                                "name" to it.name,
                                "text" to it.text
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

// ── 合并后的 input 数据类 ──

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

private sealed interface CardGroupQuery {
    data object ListAction : CardGroupQuery
    data class GetAction(val managerId: String) : CardGroupQuery
}

private data class CardGroupInput(
    @field:JsonPropertyDescription("操作类型：LIST 列出所有方案摘要，GET 读取方案详情（需传 managerId）")
    val action: String,
    @field:JsonPropertyDescription("方案 id，仅 action=GET 时需要，由 card_group(action=LIST) 返回。")
    val managerId: String? = null
) {
    fun toQuery(): CardGroupQuery = when (action.uppercase()) {
        "GET" -> {
            val managerId = managerId
            if (managerId.isNullOrBlank()) throw McpBadInput("action=GET 需要 managerId 参数") else CardGroupQuery.GetAction(
                managerId
            )
        }

        "LIST" -> CardGroupQuery.ListAction
        else -> throw McpBadInput("未知 action: $action")
    }
}

// ── 保留的 input 数据类 ──

// ── 保留的 input 数据类 ──

private data class SaveCardGroupInput(
    @field:JsonPropertyDescription("卡池来源文件名（不含扩展名）。正常模式必填；克隆模式下可省略（缺省使用克隆源的 sourceFile，也可显式覆盖）。")
    val sourceFile: String? = null,
    @field:JsonPropertyDescription("方案名称，缺省使用 sourceFile。cloneFrom 非空且不提供时自动加 \"副本\" 后缀。")
    val managerName: String? = null,
    @field:JsonPropertyDescription("已有方案的 id（由 card_group(action=LIST) 获取），非空则更新该方案。与 cloneFrom 互斥。")
    val existingId: String? = null,
    @field:JsonPropertyDescription("分组列表。cloneFrom 非空时忽略此字段（使用克隆源的 binding）。")
    val bindings: List<SaveCardGroupBindingInput> = emptyList(),
    @field:JsonPropertyDescription("克隆已有分组方案的 id（由 card_group(action=LIST) 获取）。与 existingId 互斥；提供 cloneFrom 时 sourceFile/bindings 可省略。非空时以该方案为蓝本创建副本，含所有 binding 与 behavior。")
    val cloneFrom: String? = null
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

private data class DeleteCardGroupInput(
    @field:JsonPropertyDescription("要删除的方案 id，由 card_group(action=LIST) 获取。删除不可恢复。")
    val managerId: String
)

private data class DeleteCardPoolInput(
    @field:JsonPropertyDescription("要删除的卡池文件名（不含 .cardgroup 后缀），由 card_pool(action=LIST) 获取。")
    val fileName: String
)
