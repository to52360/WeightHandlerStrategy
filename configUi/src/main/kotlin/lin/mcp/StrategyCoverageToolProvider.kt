package lin.mcp

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import lin.mcp.coverage.CoverageKind
import lin.mcp.coverage.CoverageSource
import lin.mcp.coverage.coverageSources
import lin.rule.tree.CardGroupBinding
import lin.rule.tree.CardGroupManagerConfig
import lin.rule.tree.findOverride

/**
 * 策略覆盖总览（进度恢复 / 续接诊断，2026-08-09 方案 A）。
 *
 * 一次返回指定卡组的分组策略覆盖状态，替代人工交叉对照 evaluator_tree / combo_plan / aura_boost /
 * purpose_tag 多个查询。策略分散在多类机制，无工具时"哪些分组没策略"只能靠人工交叉对照。
 *
 * ## 扩展点（加一类覆盖语义 = 加一个函数 + 加一行）
 * 覆盖来源由 [coverageSources] **声明式注册**：「哪些机制算覆盖、算哪一类」不再写死在 status 分支里，
 * 而由 [CoverageSource.kind] 表达 —— 加"时序"这类新语义只需加一个函数 + 一行注册，本类的 status
 * 判定与输出组装**一行都不用动**（旧形态要改 5 处，漏改即静默不生效）。
 *
 * ## status 语义（由 kind 驱动）
 * - COVERED：任一 [CoverageKind.SCORING] 来源命中（评估树 GROUP 绑定 / combo / 光环 group_filter）
 * - ORCHESTRATED：无评分来源命中，但任一 [CoverageKind.ORCHESTRATION] 来源命中（stage 覆盖 / 条件 stage）
 * - UNCOVERED：以上全无
 * 组内卡关联的用途标签（groupPurposeTags）作为辅助信息展示，不算分组覆盖依据（全局兜底非分组策略）。
 *
 * ## 卡级覆盖分层
 * - uncoveredCards：无分组、无标签、无 CARD 树的裸卡（完全无机制）
 * - tagOnlyCards：有标签但无分组、无 CARD 树（标签只够全局兜底，无卡组特异性正向出牌策略）
 * 两者互补，共同暴露"未获得卡组特异性出牌策略"的卡（未进组且无 CARD 树）。
 *
 * ## 职责边界
 * 本类只做三件事：**取快照 → 组装视图 → 输出**。读库与解析全部在 [ConfigSnapshotAssembler]（单点装配），
 * 覆盖语义全部在 [coverageSources]（声明式）——本类不认识任何一类具体来源。
 */
class StrategyCoverageToolProvider(
    private val assembler: ConfigSnapshotAssembler,
    private val sources: List<CoverageSource> = coverageSources
) : McpToolProvider {

    override fun provide(): List<McpToolHandler> = listOf(
        typedTool<StrategyCoverageInput>(
            name = "strategy_coverage",
            description = """
                策略覆盖总览（进度恢复 / 续接诊断）。一次返回指定卡组全部分组及其策略覆盖状态，
                替代人工分别查询评估树 / combo / 光环 / 用途标签多个工具。
                分组 status：COVERED（有评估树绑定 / combo / AuraBoost 评分机制）> ORCHESTRATED（仅 stageOverride /
                conditionalStage 排序机制）> UNCOVERED（无任何机制）。
                分组 coverage 字段逐来源列出命中条目：evaluatorTrees / combo / auraBoost（以上评分类）+
                orchestration（排序类，即该组为何是 ORCHESTRATED 的依据）。
                同时输出：
                - uncoveredCards：不在任何分组、无用途标签、且无 CARD 单卡树的裸卡
                - tagOnlyCards：有用途标签、但不在任何分组、且无 CARD 单卡树的卡（标签只够全局兜底，无卡组特异性
                  正向出牌策略，覆盖质量缺口——注意"有标签 ≠ 有评分覆盖"）
                - cardTrees：该卡组卡池中被 CARD 绑定单卡树覆盖的卡清单（CARD 树是全局资源，按卡池归属展示；
                  单卡微观规则走 CARD 绑定）
                managerId 由 card_group(action=LIST) 获取；不传则返回全部卡组。
            """.trimIndent()
        ) { input ->
            mcpSuccess(buildCoverage(input.managerId))
        }
    )

    private fun buildCoverage(managerId: String?): Map<String, Any?> {
        val snap = assembler.assemble(managerId)
        val managers = snap.managers.filter { managerId == null || it.cardGroupManagerId == managerId }
        return mapOf(
            "managerId" to managerId,
            "managers" to managers.map { buildManagerView(it, snap) }
        )
    }

    /** 组装单个卡组方案的覆盖视图（分组状态 + 裸卡 + CARD 树清单）。 */
    private fun buildManagerView(mgr: CardGroupManagerConfig, snap: ConfigSnapshot): Map<String, Any?> {
        val meta = snap.managerMeta[mgr.cardGroupManagerId]
        val poolCards = snap.cardPools[meta?.sourceFile]?.cards.orEmpty().map { it.cardId }

        val groups = mgr.bindings.map { binding -> buildGroupView(binding, snap) }

        val groupedCardIds = mgr.bindings.flatMap { it.cardIds }.toSet()
        // CARD 树清单：该卡组卡池中被 CARD 绑定单卡树覆盖的卡（cardId -> 树列表）
        val cardTrees = poolCards
            .filter { snap.cardTreeByCard.containsKey(it) }
            .map { cardId ->
                mapOf(
                    "cardId" to cardId,
                    "trees" to snap.cardTreeByCard[cardId].orEmpty(),
                    "inGroup" to (cardId in groupedCardIds),
                    "purposeTags" to snap.tagsByCard[cardId].orEmpty()
                )
            }
        // 裸卡 = 不在任何分组、无用途标签、且无 CARD 单卡树（有 CARD 树即视为已有评分机制，非裸奔）
        val uncoveredCards = poolCards
            .filter { cardId ->
                cardId !in groupedCardIds && snap.tagsByCard[cardId].isNullOrEmpty() &&
                        !snap.cardTreeByCard.containsKey(cardId)
            }
            .map { cardId -> mapOf("cardId" to cardId, "purposeTags" to snap.tagsByCard[cardId].orEmpty()) }
        // 有标签无激励卡 = 有用途标签、但不在任何分组、且无 CARD 单卡树（标签只够全局兜底，无卡组特异性正向出牌策略）
        // 正是 uncoveredCards 判定中"因有标签而被漏报"的那部分卡，单独成清单暴露覆盖质量缺口。
        val tagOnlyCards = poolCards
            .filter { cardId ->
                cardId !in groupedCardIds && !snap.tagsByCard[cardId].isNullOrEmpty() &&
                        !snap.cardTreeByCard.containsKey(cardId)
            }
            .map { cardId -> mapOf("cardId" to cardId, "purposeTags" to snap.tagsByCard[cardId].orEmpty()) }

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
                "tagOnlyCardCount" to tagOnlyCards.size,
                "cardTreeCardCount" to cardTrees.size
            ),
            "groups" to groups,
            "uncoveredGroups" to uncoveredGroupIds,
            "uncoveredCards" to uncoveredCards,
            "tagOnlyCards" to tagOnlyCards,
            "cardTrees" to cardTrees
        )
    }

    /**
     * 组装单个分组的覆盖视图（覆盖机制 + 覆盖状态）。
     *
     * `coverage` = 各来源命中条目（键顺序 = [coverageSources] 注册顺序）；
     * `status` = 按来源的 [CoverageKind] 判定，**本方法不再认识任何一类具体机制**。
     */
    private fun buildGroupView(binding: CardGroupBinding, snap: ConfigSnapshot): Map<String, Any?> {
        val override = binding.behaviors.findOverride()
        val bySource = sources.associate { it.key to it.entriesFor(binding.id, snap) }
        val groupTags = binding.cardIds.flatMap { snap.tagsByCard[it].orEmpty() }.distinct().sorted()

        val status = when {
            sources.any { it.kind == CoverageKind.SCORING && bySource[it.key].orEmpty().isNotEmpty() } ->
                "COVERED"

            sources.any { it.kind == CoverageKind.ORCHESTRATION && bySource[it.key].orEmpty().isNotEmpty() } ->
                "ORCHESTRATED"

            else -> "UNCOVERED"
        }

        return mapOf(
            "id" to binding.id,
            "name" to binding.name,
            "description" to (binding.description ?: ""),
            "cardIds" to binding.cardIds,
            "stageOverride" to override?.stageOverride?.name,
            "conditionalStage" to (override?.conditionalStage != null),
            "coverage" to bySource,
            "groupPurposeTags" to groupTags,
            "status" to status
        )
    }
}

private data class StrategyCoverageInput(
    @field:JsonPropertyDescription("可选：卡组 managerId（card_group(action=LIST) 获取）。不传则返回全部卡组。")
    val managerId: String? = null
)
