package lin.mcp

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import lin.dao.CardGroupConfig
import lin.dao.CardGroupJsonParser
import lin.repository.aura_boost.AuraBoostConfigService
import lin.repository.aura_boost.AuraBoostEntity
import lin.repository.card_group.CardGroupService
import lin.repository.card_group.CardManagerEntity
import lin.repository.card_purpose.CardPurposeRepository
import lin.repository.combo_plan.ComboPlanDefinitionEntity
import lin.repository.combo_plan.ComboPlanDefinitionRepository
import lin.repository.condition_tree.ConditionTreeConfigService
import lin.rule.condition.ConditionPayload
import lin.rule.condition.collectConditionRefs
import lin.rule.tree.CardGroupBinding
import lin.rule.tree.CardGroupManagerConfig
import lin.rule.tree.EvaluatorTreeBindingType
import lin.rule.tree.findOverride
import lin.ui.service.TreeConfigService

/**
 * 策略覆盖总览（进度恢复 / 续接诊断，2026-08-09 方案 A）。
 *
 * 一次返回指定卡组的分组策略覆盖状态，替代人工交叉对照 evaluator_tree / combo_plan / aura_boost /
 * purpose_tag 多个查询。策略分散在 4 类机制，无工具时"哪些分组没策略"只能靠人工交叉对照。
 *
 * 覆盖机制与 status 语义：
 * - COVERED：有评分类机制（评估树 GROUP 绑定 / combo_plan core|dep / AuraBoost 条件树 group_filter 引用）
 * - ORCHESTRATED：无评分机制，但仅有排序机制（stageOverride / conditionalStage）
 * - UNCOVERED：以上全无
 * 组内卡关联的用途标签（groupPurposeTags）作为辅助信息展示，不算分组覆盖依据（全局兜底非分组策略）。
 */
class StrategyCoverageToolProvider(
    private val cardGroupService: CardGroupService,
    private val treeConfigService: TreeConfigService,
    private val comboPlanRepository: ComboPlanDefinitionRepository,
    private val auraBoostService: AuraBoostConfigService,
    private val conditionTreeService: ConditionTreeConfigService,
    private val cardPurposeRepository: CardPurposeRepository
) : McpToolProvider {

    override fun provide(): List<McpToolHandler> = listOf(
        typedTool<StrategyCoverageInput>(
            name = "strategy_coverage",
            description = """
                策略覆盖总览（进度恢复 / 续接诊断）。一次返回指定卡组全部分组及其策略覆盖状态，
                替代人工交叉对照 evaluator_tree / combo_plan / aura_boost / purpose_tag 多个查询。
                分组 status：COVERED（有评估树绑定 / combo / AuraBoost 评分机制）> ORCHESTRATED（仅 stageOverride /
                conditionalStage 排序机制）> UNCOVERED（无任何机制）。
                同时输出：
                - uncoveredCards：不在任何分组、无用途标签、且无 CARD 单卡树的裸卡
                - cardTrees：该卡组卡池中被 CARD 绑定单卡树覆盖的卡清单（CARD 树是全局资源，按卡池归属展示；
                  单卡微观规则如"神性圣契非 0 费卡手扣分"走 CARD 绑定）
                managerId 由 card_group(action=LIST) 获取；不传则返回全部卡组。
            """.trimIndent()
        ) { input ->
            mcpSuccess(buildCoverage(input.managerId))
        }
    )

    private fun buildCoverage(managerId: String?): Map<String, Any?> {
        val managers = cardGroupService.loadAll()
            .filter { managerId == null || it.cardGroupManagerId == managerId }
        val ctx = buildCoverageContext(managerId)
        val managerViews = managers.map { buildManagerView(it, ctx) }
        return mapOf(
            "managerId" to managerId,
            "managers" to managerViews
        )
    }

    /** 一次性构建全部覆盖索引（评估树 GROUP/CARD、combo、AuraBoost、用途标签、manager 元数据、卡池）。 */
    private fun buildCoverageContext(managerId: String?): CoverageContext {
        // 评估树：GROUP 绑定条目 id -> 树摘要（含全局共享树）
        val treeByBinding = treeConfigService.loadAll()
            .mapNotNull { (entity, config) -> config?.let { entity to it } }
            .filter { (_, config) -> config.bindingType == EvaluatorTreeBindingType.GROUP }
            .flatMap { (entity, config) -> config.bindingIds.map { bid -> bid to entity } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, list) -> list.map { mapOf("id" to it.id, "name" to it.name) }.distinct() }

        // 评估树：CARD 绑定单卡树 cardId -> 树摘要（CARD 树是全局资源，managerId 为 null，按卡池归属展示）
        val cardTreeByCard = treeConfigService.loadAll()
            .mapNotNull { (entity, config) -> config?.let { entity to it } }
            .filter { (_, config) -> config.bindingType == EvaluatorTreeBindingType.CARD }
            .flatMap { (entity, config) -> config.bindingIds.map { cardId -> cardId to entity } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, list) -> list.map { mapOf("id" to it.id, "name" to it.name) }.distinct() }

        // combo：groupId -> [(combo, role)]（core / dep）
        val comboByGroup = mutableMapOf<String, MutableList<Pair<ComboPlanDefinitionEntity, String>>>()
        comboPlanRepository.findAll()
            .filter { managerId == null || it.managerId == managerId }
            .forEach { c ->
                c.coreGroupIds.split(",").filter { it.isNotBlank() }
                    .forEach { gid -> comboByGroup.getOrPut(gid) { mutableListOf() }.add(c to "core") }
                c.depGroupIds.split(",").filter { it.isNotBlank() }
                    .forEach { gid -> comboByGroup.getOrPut(gid) { mutableListOf() }.add(c to "dep") }
            }

        // AuraBoost：条件树内 group_filter 引用 -> groupId -> boost（via = condition / target）
        val boostByGroup = mutableMapOf<String, MutableList<Pair<AuraBoostEntity, String>>>()
        auraBoostService.loadAll()
            .filter { managerId == null || it.managerId == managerId }
            .forEach { boost ->
                extractGroupFilterIds(boost.conditionId).forEach { gid ->
                    boostByGroup.getOrPut(gid) { mutableListOf() }.add(boost to "condition")
                }
                extractGroupFilterIds(boost.targetConditionId).forEach { gid ->
                    boostByGroup.getOrPut(gid) { mutableListOf() }.add(boost to "target")
                }
            }

        // 用途标签：cardId -> tags
        val tagsByCard = cardPurposeRepository.findAll()
            .associateBy({ it.cardId }) { entity ->
                entity.purposeTags.split(",").map(String::trim).filter { it.isNotBlank() }
            }

        return CoverageContext(
            treeByBinding = treeByBinding,
            cardTreeByCard = cardTreeByCard,
            comboByGroup = comboByGroup,
            boostByGroup = boostByGroup,
            tagsByCard = tagsByCard,
            managerMeta = cardGroupService.loadAllManagers().associateBy { it.id },
            cardPools = CardGroupJsonParser.loadAllCardGroups().toMap()
        )
    }

    /** 组装单个卡组方案的覆盖视图（分组状态 + 裸卡 + CARD 树清单）。 */
    private fun buildManagerView(mgr: CardGroupManagerConfig, ctx: CoverageContext): Map<String, Any?> {
        val meta = ctx.managerMeta[mgr.cardGroupManagerId]
        val poolCards = ctx.cardPools[meta?.sourceFile]?.cards.orEmpty().map { it.cardId }

        val groups = mgr.bindings.map { binding -> buildGroupView(binding, ctx) }

        val groupedCardIds = mgr.bindings.flatMap { it.cardIds }.toSet()
        // CARD 树清单：该卡组卡池中被 CARD 绑定单卡树覆盖的卡（cardId -> 树列表）
        val cardTrees = poolCards
            .filter { ctx.cardTreeByCard.containsKey(it) }
            .map { cardId ->
                mapOf(
                    "cardId" to cardId,
                    "trees" to ctx.cardTreeByCard[cardId].orEmpty(),
                    "inGroup" to (cardId in groupedCardIds),
                    "purposeTags" to ctx.tagsByCard[cardId].orEmpty()
                )
            }
        // 裸卡 = 不在任何分组、无用途标签、且无 CARD 单卡树（有 CARD 树即视为已有评分机制，非裸奔）
        val uncoveredCards = poolCards
            .filter { cardId ->
                cardId !in groupedCardIds && ctx.tagsByCard[cardId].isNullOrEmpty() && !ctx.cardTreeByCard.containsKey(
                    cardId
                )
            }
            .map { cardId -> mapOf("cardId" to cardId, "purposeTags" to ctx.tagsByCard[cardId].orEmpty()) }

        val uncoveredGroupIds = groups.asSequence()
            .filter { it["status"] == "UNCOVERED" }
            .map { mapOf("id" to it["id"], "name" to it["name"]) }
            .toList()

        return mapOf(
            "id" to mgr.cardGroupManagerId,
            "name" to mgr.name,
            "sourceFile" to (meta?.sourceFile ?: ""),
            "enabled" to mgr.enabled,
            "description" to (meta?.description ?: ""),
            "status" to (meta?.status ?: ""),
            "summary" to mapOf(
                "groupCount" to groups.size,
                "coveredGroups" to groups.count { it["status"] == "COVERED" },
                "orchestratedGroups" to groups.count { it["status"] == "ORCHESTRATED" },
                "uncoveredGroups" to groups.count { it["status"] == "UNCOVERED" },
                "uncoveredCardCount" to uncoveredCards.size,
                "cardTreeCardCount" to cardTrees.size
            ),
            "groups" to groups,
            "uncoveredGroups" to uncoveredGroupIds,
            "uncoveredCards" to uncoveredCards,
            "cardTrees" to cardTrees
        )
    }

    /** 组装单个分组的覆盖视图（覆盖机制 + 覆盖状态）。 */
    private fun buildGroupView(binding: CardGroupBinding, ctx: CoverageContext): Map<String, Any?> {
        val override = binding.behaviors.findOverride()
        val coverageTrees = ctx.treeByBinding[binding.id].orEmpty()
        val coverageCombos = ctx.comboByGroup[binding.id].orEmpty()
        val coverageBoosts = ctx.boostByGroup[binding.id].orEmpty()
        val groupTags = binding.cardIds.flatMap { ctx.tagsByCard[it].orEmpty() }.distinct().sorted()

        val hasScore = coverageTrees.isNotEmpty() || coverageCombos.isNotEmpty() || coverageBoosts.isNotEmpty()
        val hasOrchestration = override?.stageOverride != null || override?.conditionalStage != null
        val status = when {
            hasScore -> "COVERED"
            hasOrchestration -> "ORCHESTRATED"
            else -> "UNCOVERED"
        }

        return mapOf(
            "id" to binding.id,
            "name" to binding.name,
            "description" to (binding.description ?: ""),
            "cardIds" to binding.cardIds,
            "stageOverride" to override?.stageOverride?.name,
            "conditionalStage" to (override?.conditionalStage != null),
            "coverage" to mapOf(
                "evaluatorTrees" to coverageTrees,
                "combo" to coverageCombos.map { (c, role) ->
                    mapOf("id" to c.id, "relation" to c.relation, "role" to role, "score" to c.score)
                },
                "auraBoost" to coverageBoosts.map { (b, via) ->
                    mapOf("id" to b.id, "name" to (b.name ?: ""), "via" to via, "score" to b.score)
                }
            ),
            "groupPurposeTags" to groupTags,
            "status" to status
        )
    }

    /** 解析条件树内 group_filter transform 引用的分组 id（AuraBoost 覆盖判定）。 */
    private fun extractGroupFilterIds(conditionTreeId: String): Set<String> {
        val config = conditionTreeService.findById(conditionTreeId) ?: return emptySet()
        return config.root.collectConditionRefs()
            .asSequence()
            .filterIsInstance<ConditionPayload.PipelineRef>()
            .flatMap { ref -> ref.transforms.asSequence() }
            .filter { it.transformId == "group_filter" }
            .mapNotNull { it.args["groupId"] as? String }
            .toSet()
    }
}

/** 策略覆盖计算的共享索引（避免 buildCoverage 长方法内平铺全部临时 Map）。 */
private class CoverageContext(
    val treeByBinding: Map<String, List<Map<String, String>>>,
    val cardTreeByCard: Map<String, List<Map<String, String>>>,
    val comboByGroup: Map<String, List<Pair<ComboPlanDefinitionEntity, String>>>,
    val boostByGroup: Map<String, List<Pair<AuraBoostEntity, String>>>,
    val tagsByCard: Map<String, List<String>>,
    val managerMeta: Map<String, CardManagerEntity>,
    val cardPools: Map<String, CardGroupConfig>
)

private data class StrategyCoverageInput(
    @field:JsonPropertyDescription("可选：卡组 managerId（card_group(action=LIST) 获取）。不传则返回全部卡组。")
    val managerId: String? = null
)
