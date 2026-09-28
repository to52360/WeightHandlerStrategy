package lin.mcp

import lin.dao.CardGroupJsonParser
import lin.dao.CardWeightConfig
import lin.mcp.coverage.CoverageKind
import lin.mcp.coverage.coverageSources
import org.junit.Assert.*
import org.junit.Test

/**
 * T-FO-020（S1+S2 覆盖侧扩展点迁移）：`strategy_coverage` 的契约与扩展点验证。
 *
 * 覆盖三点：
 * ① 覆盖来源由 [coverageSources] **声明式注册**（键顺序 + kind 是契约：加一类覆盖语义 = 加一行）；
 * ② status 由 kind 驱动（SCORING ⇒ COVERED；仅 ORCHESTRATION ⇒ ORCHESTRATED；全无 ⇒ UNCOVERED）；
 * ③ 四个来源各自命中时 `coverage.<key>` 的形态，且卡级分类清单与 summary 计数自洽。
 *
 * 环境（Koin / DB / 工具注册 / 清理）复用 [McpTestEnv]；本类只造夹具 + 写断言。
 */
class StrategyCoverageTest : McpTestEnv() {

    private val poolName = "tfo020_cov_deck"
    private val managerName = "tfo020_cov_groups"

    private val treeCard = "ICC_820"
    private val comboCard = "GDB_138"
    private val orchestrationCard = "WW_051"
    private val bareCard = "JAM_006"

    /** 造卡池（4 张：树覆盖 / combo / 仅编排 / 裸卡）。 */
    private fun createPool() {
        savedFile = CardGroupJsonParser.saveCardGroupConfigs(
            configs = listOf(
                CardWeightConfig(cardId = treeCard, name = "树覆盖卡"),
                CardWeightConfig(cardId = comboCard, name = "combo卡"),
                CardWeightConfig(cardId = orchestrationCard, name = "仅编排卡"),
                CardWeightConfig(cardId = bareCard, name = "裸卡")
            ),
            groupName = poolName,
            enabled = true
        )
    }

    /** 夹具：三个分组（树覆盖 / combo 核心 / 仅编排）+ 卡池里一张不分组裸卡。返回分组名 → bindingId。 */
    private fun buildFixture(): Map<String, String> {
        createPool()
        val resp = call(
            "save_card_group",
            """{"sourceFile":"$poolName","managerName":"$managerName","bindings":[
                {"name":"树覆盖组","cardIds":["$treeCard"]},
                {"name":"combo核心组","cardIds":["$comboCard"]},
                {"name":"仅编排组","cardIds":["$orchestrationCard"]}]}"""
        )
        assertFalse("建卡组应成功：${resp.contentJson}", resp.isError)
        managerId = mapper.readValue(resp.contentJson, Map::class.java)["managerId"] as String
        val mid = managerId!!

        val bindings = mapper.readTree(call("get", """{"resource":"card_group","id":"$mid"}""").contentJson)["bindings"]
        val idByName = bindings.associate { it["name"].asText() to it["id"].asText() }
        assertEquals("夹具应有 3 个分组：$idByName", 3, idByName.size)
        return idByName
    }

    /** 给某分组挂一棵 GROUP 绑定评估树（走草稿 → 填充叶 → 提交，与 McpToolDriverTest 同配方）。 */
    private fun attachGroupTree(mid: String, bindingId: String): String {
        val draft = call(
            "create_draft_tree",
            """{"name":"tfo020_cov_tree","root":{"OrNode":{"children":[{"Leaf":{"payload":{"Rule":{"nodeId":"leaf1"}}}}]}},
                "bindingType":"GROUP","bindingIds":["$bindingId"],"managerId":"$mid"}"""
        )
        assertFalse("建草稿应成功：${draft.contentJson}", draft.isError)
        val draftId = mapper.readTree(draft.contentJson)["draftId"].asText()
        call(
            "put_draft_leaf",
            """{"draftId":"$draftId","nodeId":"leaf1","leafConfig":{"ORTHOGONAL_CONDITION":{
                "nodeId":"leaf1","sourceId":"orthogonal_condition",
                "guardCondition":{"PipelineRef":{"sourceId":"hand_cards","transforms":[{"transformId":"count_projection","args":{}}],
                "operatorId":"gte","operatorArgs":{"threshold":1},"refId":"leaf1"}},
                "scoreEffect":{"ConstantScore":{"value":3.2}},"args":{},"guardMissBehavior":"SCORE"}}}"""
        )
        val commit = call("commit_draft_tree", """{"draftId":"$draftId"}""")
        assertFalse("提交树应成功：${commit.contentJson}", commit.isError)
        val treeId = mapper.readTree(commit.contentJson)["id"].asText()
        this.treeId = treeId
        return treeId
    }

    @Suppress("UNCHECKED_CAST")
    private fun managersOf(resp: McpToolResult): List<Map<String, Any?>> {
        val body = mapper.readValue(resp.contentJson, Map::class.java) as Map<String, Any?>
        return (body["managers"] as? List<Map<String, Any?>>).orEmpty()
    }

    @Suppress("UNCHECKED_CAST")
    private fun groupsOf(view: Map<String, Any?>): Map<String, Map<String, Any?>> =
        (view["groups"] as? List<Map<String, Any?>>).orEmpty().associateBy { it["name"].toString() }

    @Suppress("UNCHECKED_CAST")
    private fun coverageOf(group: Map<String, Any?>): Map<String, Any?> =
        group["coverage"] as Map<String, Any?>

    /** ① 四类来源声明式注册：键顺序与 kind 是契约（加一类覆盖语义 = 在此清单加一行）。 */
    @Test
    fun `覆盖来源清单键顺序与类别固定`() {
        assertEquals(
            "来源键顺序 = 输出 coverage 字段顺序（先评分后编排）",
            listOf("evaluatorTrees", "combo", "auraBoost", "orchestration"),
            coverageSources.map { it.key }
        )
        assertEquals(
            "前三类为评分来源，orchestration 为编排来源",
            listOf(
                CoverageKind.SCORING, CoverageKind.SCORING, CoverageKind.SCORING, CoverageKind.ORCHESTRATION
            ),
            coverageSources.map { it.kind }
        )

        // 空快照下每个来源都必须返回空列表（Provider 依赖「无覆盖 ⇒ 空列表」而非 null）
        val empty = ConfigSnapshot(
            managers = emptyList(),
            bindingsById = emptyMap(),
            managerMeta = emptyMap(),
            cardPools = emptyMap(),
            tagsByCard = emptyMap(),
            treeByBinding = emptyMap(),
            cardTreeByCard = emptyMap(),
            comboByGroup = emptyMap(),
            boostByGroup = emptyMap()
        )
        coverageSources.forEach { source ->
            assertTrue("来源 ${source.key} 在空快照下应返回空列表", source.entriesFor("no_such_binding", empty).isEmpty())
        }
    }

    /** ②③ 端到端：status 由 kind 驱动，四个来源各自命中时 coverage 形态正确。 */
    @Test
    fun `status 由来源类别驱动且各来源条目形态正确`() {
        val idByName = buildFixture()
        val mid = managerId!!
        val treeId = attachGroupTree(mid, idByName.getValue("树覆盖组"))
        call("group_override", """{"bindingId":"${idByName.getValue("仅编排组")}","stageOverride":"SETUP"}""")

        val comboResp = call(
            "save_combo_plan",
            """{"managerId":"$mid","coreGroupIds":["${idByName.getValue("combo核心组")}"],
                "depGroupIds":["${idByName.getValue("树覆盖组")}"],"score":4.0,"relation":"CORE_BEFORE_DEP"}"""
        )
        assertFalse("建 combo 应成功：${comboResp.contentJson}", comboResp.isError)
        val comboId = mapper.readTree(comboResp.contentJson)["id"].asText()

        val resp = call("strategy_coverage", """{"managerId":"$mid"}""")
        assertFalse("覆盖查询应成功：${resp.contentJson}", resp.isError)
        val view = managersOf(resp).single()
        val groups = groupsOf(view)
        assertEquals("夹具应有 4 组（含裸组？裸卡不分组）", 3, groups.size)

        // ── 树覆盖组：SCORING 命中 ⇒ COVERED，条目带树 id ──
        val treeGroup = groups.getValue("树覆盖组")
        assertEquals("COVERED", treeGroup["status"])
        val treeCoverage = coverageOf(treeGroup)
        assertEquals("coverage 键集合 = 来源清单", coverageSources.map { it.key }, treeCoverage.keys.toList())
        assertEquals("应列出挂载的树", treeId, (treeCoverage["evaluatorTrees"] as List<*>).firstOrNull()?.let {
            (it as Map<*, *>)["id"]
        })
        // 该组同时是 combo 的 dep 侧（夹具：core=combo核心组 → dep=树覆盖组）⇒ 两类来源可叠加
        assertEquals(
            "该组应作为 dep 侧被 combo 引用",
            "dep",
            ((treeCoverage["combo"] as List<*>).first() as Map<*, *>)["role"]
        )
        assertTrue("该组无光环", (treeCoverage["auraBoost"] as List<*>).isEmpty())
        assertTrue("该组无编排机制", (treeCoverage["orchestration"] as List<*>).isEmpty())

        // ── combo 核心组：SCORING 命中（combo）⇒ COVERED，role=core ──
        val comboGroup = groups.getValue("combo核心组")
        assertEquals("COVERED", comboGroup["status"])
        val comboEntry = (coverageOf(comboGroup)["combo"] as List<*>).first() as Map<*, *>
        assertEquals(comboId, comboEntry["id"])
        assertEquals("core", comboEntry["role"])

        // ── 仅编排组：只有 ORCHESTRATION 命中 ⇒ ORCHESTRATED，且 orchestration 条目可自证 status ──
        val orchestrationGroup = groups.getValue("仅编排组")
        assertEquals("ORCHESTRATED", orchestrationGroup["status"])
        val orchestrationCoverage = coverageOf(orchestrationGroup)
        assertTrue("评分来源应全空", (orchestrationCoverage["evaluatorTrees"] as List<*>).isEmpty())
        val orchestrationEntry = (orchestrationCoverage["orchestration"] as List<*>).first() as Map<*, *>
        assertEquals("SETUP", orchestrationEntry["stageOverride"])
        assertEquals(false, orchestrationEntry["conditionalStage"])
        assertEquals("SETUP", orchestrationGroup["stageOverride"])

        // ── 汇总计数与逐组状态自洽 ──
        @Suppress("UNCHECKED_CAST")
        val summary = view["summary"] as Map<String, Any?>
        assertEquals(3, (summary["groupCount"] as Number).toInt())
        assertEquals(2, (summary["coveredGroups"] as Number).toInt())
        assertEquals(1, (summary["orchestratedGroups"] as Number).toInt())
        assertEquals(0, (summary["uncoveredGroups"] as Number).toInt())
        assertTrue("无 UNCOVERED 组 ⇒ uncoveredGroups 清单为空", (view["uncoveredGroups"] as List<*>).isEmpty())

        // ── 卡级：池内未分组的卡进 uncoveredCards（无标签、无 CARD 树）──
        val uncovered = (view["uncoveredCards"] as List<*>).map { (it as Map<*, *>)["cardId"] }
        assertTrue("裸卡 $bareCard 应出现在 uncoveredCards：$uncovered", uncovered.contains(bareCard))
        assertEquals("计数应与清单长度一致", uncovered.size, (summary["uncoveredCardCount"] as Number).toInt())
    }
}
