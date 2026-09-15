package lin.mcp

import lin.repository.card_group.CardGroupRepository
import lin.repository.card_group.DimensionScope
import lin.repository.card_group.StrategyPresetRepository
import lin.repository.delete_snapshot.DeleteSnapshotEntity
import lin.repository.delete_snapshot.DeleteSnapshotRepository
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.koin.core.context.GlobalContext
import org.springframework.jdbc.core.JdbcTemplate
import java.nio.file.Files
import java.nio.file.Path

/**
 * T-010 回归（sop-rework，2026-09-05）：delete 快照落 delete_snapshot 表 + restore_snapshot 一键恢复。
 *
 * - 逐资源 roundtrip：delete → 拿 snapshotId → restore_snapshot → 数据与删除前一致（原 id / 绑定 / 关联树保留）。
 * - card_group 级联：恢复后 manager + bindings + 关联树全回，binding/tree id 不变。
 * - card_pool 文件：恢复后 .cardgroup 重建、cards 全回。
 * - 冲突：restore 前占用原 id → 报错拒绝且不覆盖。
 * - 保留 N 条：repository.trimTo 只保留最新 N 条。
 * 基于 McpTestEnv 根源库 + 自建数据 + @After 级联清理。
 */
class DeleteSnapshotRestoreTest : McpTestEnv() {

    private val deckCode =
        "AAEBAZ8FBPfQArjFBdaABtK5Bg3YxwKd7ALZ/gL9uAPruQPTvQTi0wSZjgb1lQbt3waS4Aac6Aaf6AYAAA=="
    private val managerName = "t010_restore_groups"
    private val poolName = "t010_restore_pool"

    /** save_condition_tree 的 treeJson 是 String 字段，须传 JSON 编码后的字符串字面量（T011 同款 double-encode）。 */
    private fun treeJsonParam(): String = mapper.writeValueAsString(spellTreeJson)

    // 「所有法术」条件树（与 SavePredicateGroupTest / T011ProviderTxSinkTest 同构，inlineCreated 参数校验可过）
    private val spellTreeJson =
        """{"id":"t010_spell_tree","name":"t010_spell_tree","root":{"Leaf":{"payload":{"PipelineRef":{"refId":"t1","sourceId":"evaluating_card","transforms":[{"transformId":"to_card","args":{}}],"operatorId":"is_card_type","operatorArgs":{"targetType":"SPELL"}}}}}}"""

    @Before
    fun setUpPool() {
        cleanupAll(poolName, managerName)
        val resp = call(
            "parse_hearthstone_deck_code",
            """{"deckCode":"$deckCode","groupName":"$poolName","enabled":true}"""
        )
        assertFalse("卡池文件创建失败: ${resp.contentJson}", resp.isError)
        // 卡池文件由 @After savedFile 清理
        savedFile = Path.of("../data/cardgroup/$poolName.cardgroup")
    }

    // ─────────────────── aura_boost roundtrip ───────────────────

    @Test
    fun `auraBoost delete然后restore恢复原id`() {
        // 建一个独立 condition tree 供 boost 引用（避免内联建树把引用树挂在 manager 上影响清理）
        val treeResp = call("save_condition_tree", """{"name":"t010_boost_cond","treeJson":${treeJsonParam()}}""")
        assertFalse(treeResp.isError)
        val condId = mapper.readTree(treeResp.contentJson)["id"].asText()
        trackConditionTree(condId)

        val saveResp = call(
            "save_aura_boost",
            """{"name":"t010_boost_rt","score":2.5,"managerId":"$managerName",
                "conditionId":"$condId","targetConditionId":"$condId"}"""
        )
        assertFalse("save_aura_boost 应成功: ${saveResp.contentJson}", saveResp.isError)
        val boostId = mapper.readTree(saveResp.contentJson)["id"].asText()

        val del = call("delete", """{"resource":"aura_boost","id":"$boostId"}""")
        assertFalse("delete(aura_boost) 应成功: ${del.contentJson}", del.isError)
        val snapshotId = mapper.readTree(del.contentJson)["snapshotId"].asText()
        assertNotNull("delete 应返回 snapshotId", snapshotId)

        val restore = call("restore_snapshot", """{"snapshotId":"$snapshotId"}""")
        assertFalse("restore 应成功: ${restore.contentJson}", restore.isError)

        val get = call("get", """{"resource":"aura_boost","id":"$boostId"}""")
        assertFalse("恢复后 get 应成功: ${get.contentJson}", get.isError)
        val loaded = mapper.readTree(get.contentJson)
        assertEquals("t010_boost_rt", loaded["name"].asText())
        assertEquals(2.5, loaded["score"].asDouble(), 0.001)
        assertEquals(condId, loaded["conditionId"].asText())
        assertEquals(condId, loaded["targetConditionId"].asText())

        // 清理：本用例的 aura 挂在 `t010_restore_groups`（**从未建过 manager** 的名字）上
        // ⇒ McpTestEnv 的按-manager 级联清理覆盖不到，必须自己删（否则每跑一次留 1 行孤儿）
        GlobalContext.get().get<JdbcTemplate>().update("DELETE FROM aura_boost WHERE id = ?", boostId)
    }

    // ─────────────────── condition_tree roundtrip ───────────────────

    @Test
    fun `conditionTree delete然后restore恢复原id`() {
        val saveResp = call("save_condition_tree", """{"name":"t010_ct_rt","treeJson":${treeJsonParam()}}""")
        assertFalse(saveResp.isError)
        val ctId = mapper.readTree(saveResp.contentJson)["id"].asText()
        trackConditionTree(ctId)

        val del = call("delete", """{"resource":"condition_tree","id":"$ctId"}""")
        assertFalse("condition_tree 删除应成功(无引用方): ${del.contentJson}", del.isError)
        val snapshotId = mapper.readTree(del.contentJson)["snapshotId"].asText()

        val restore = call("restore_snapshot", """{"snapshotId":"$snapshotId"}""")
        assertFalse("restore 应成功: ${restore.contentJson}", restore.isError)

        val list = call("list", """{"resource":"condition_tree"}""")
        assertFalse(list.isError)
        val trees = mapper.readTree(list.contentJson)
        assertTrue(
            "恢复后应存在 id=$ctId",
            trees.any { (it["id"] as? String) == ctId || it.get("id")?.asText() == ctId }
        )
    }

    // ─────────────────── card_group 级联 roundtrip ───────────────────

    @Test
    fun `cardGroup级联 delete然后restore恢复manager加bindings加关联树原id不变`() {
        // 建方案 + 一个绑定
        val mgrResp = call(
            "save_card_group",
            """{"sourceFile":"$poolName","managerName":"$managerName","bindings":[{"name":"恢复组","cardIds":["ICC_820"]}]}"""
        )
        assertFalse("建方案应成功: ${mgrResp.contentJson}", mgrResp.isError)
        val mid = mapper.readTree(mgrResp.contentJson)["managerId"].asText()
        managerId = mid

        // 建一棵关联评估树：save_card_group 已建好 manager，直接用内部 TreeConfigService 造树较繁，
        // 这里经 AiDraftTreeToolProvider 建树成本高，改用直接 sql 建树（含 leaf 简化）模拟真实关联树
        val treeId = "t010_link_tree"
        GlobalContext.get().get<JdbcTemplate>().update(
            "INSERT INTO tree_config (id, binding_type, binding_ids, name, description, config_data, enabled, manager_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            treeId, "GROUP", mid, "关联恢复树", "desc",
            "{\"root\":{\"OrNode\":{\"children\":[]}}}", 1, mid
        )

        // delete 级联（含树）
        val del = call("delete", """{"resource":"card_group","id":"$mid"}""")
        assertFalse("delete(card_group) 应成功: ${del.contentJson}", del.isError)
        val delContent = mapper.readTree(del.contentJson)
        val snapshotId = delContent["snapshotId"].asText()
        assertNotNull("delete(card_group) 应返回 snapshotId", snapshotId)
        assertTrue("deleteTrees 应含关联树", delContent["deletedTrees"].toString().contains("关联恢复树"))

        // restore
        val restore = call("restore_snapshot", """{"snapshotId":"$snapshotId"}""")
        assertFalse("restore(card_group) 应成功: ${restore.contentJson}", restore.isError)

        // 验证 manager + binding 原 id 全回
        val jdbc = GlobalContext.get().get<JdbcTemplate>()
        val mgrCount = jdbc.queryForObject("SELECT count(*) FROM card_group_manager WHERE id = ?", Int::class.java, mid)
        assertEquals("manager 应恢复", 1, mgrCount)
        val bindCount = jdbc.queryForObject(
            "SELECT count(*) FROM card_group_binding WHERE manager_id = ?", Int::class.java, mid
        )
        assertEquals("binding 应恢复", 1, bindCount)
        // 关联树按原 id 恢复
        val treeCount = jdbc.queryForObject("SELECT count(*) FROM tree_config WHERE id = ?", Int::class.java, treeId)
        assertEquals("关联树应恢复且原 id 不变", 1, treeCount)
        val bindName = jdbc.queryForObject(
            "SELECT name FROM card_group_binding WHERE manager_id = ?", String::class.java, mid
        )
        assertEquals("恢复组", bindName)
    }

    /**
     * K-TG-014（@verify）：卡组级联删的**覆盖面** —— `aura_boost` / `combo_plan_definition` /
     * 卡组**私有**条件树此前既不在清理范围、也不在快照范围（删后留孤儿行，且 `restore_snapshot` 恢复不回）。
     * 逐表断言：删后归零 → 恢复后逐项一致（原 id + 关键字段值）。
     */
    @Test
    fun `cardGroup级联删除覆盖aura与combo与私有条件树`() {
        val jdbc = GlobalContext.get().get<JdbcTemplate>()

        // 1) 建方案 + 两个绑定（combo 的核心组 / 依赖组）
        val mgrResp = call(
            "save_card_group",
            """{"sourceFile":"$poolName","managerName":"$managerName","bindings":[
                 {"name":"从属核心组","cardIds":["ICC_820"]},
                 {"name":"从属依赖组","cardIds":["GDB_138"]}
               ]}"""
        )
        assertFalse("建方案应成功: ${mgrResp.contentJson}", mgrResp.isError)
        val mid = mapper.readTree(mgrResp.contentJson)["managerId"].asText()
        managerId = mid
        val bindingIdsFromGet = call("get", """{"resource":"card_group","id":"$mid"}""")
            .let { mapper.readTree(it.contentJson)["bindings"].map { b -> b["id"].asText() } }
        assertEquals("应有两个绑定", 2, bindingIdsFromGet.size)

        // 2) 三类从属资源：aura（内联建 2 棵**卡组私有**条件树）+ combo
        val boostResp = call(
            "save_aura_boost",
            """{"name":"t010_child_boost","score":1.5,"managerId":"$mid",
                "conditionTreeJson":${treeJsonParam()},"targetConditionTreeJson":${treeJsonParam()}}"""
        )
        assertFalse("save_aura_boost 应成功: ${boostResp.contentJson}", boostResp.isError)
        val boostId = mapper.readTree(boostResp.contentJson)["id"].asText()

        val comboResp = call(
            "save_combo_plan",
            """{"managerId":"$mid","coreGroupIds":["${bindingIdsFromGet[0]}"],
                "depGroupIds":["${bindingIdsFromGet[1]}"],"score":4.5,"relation":"SCORE_ONLY"}"""
        )
        assertFalse("save_combo_plan 应成功: ${comboResp.contentJson}", comboResp.isError)
        val comboId = mapper.readTree(comboResp.contentJson)["id"].asText()

        fun countOf(table: String): Int = jdbc.queryForObject(
            "SELECT count(*) FROM $table WHERE manager_id = ?", Int::class.java, mid
        )!!
        assertEquals("前置：1 条 aura", 1, countOf("aura_boost"))
        assertEquals("前置：1 条 combo", 1, countOf("combo_plan_definition"))
        assertEquals("前置：2 棵卡组私有条件树（内联触发 + 受益）", 2, countOf("condition_tree_config"))

        // 3) 级联删
        val del = call("delete", """{"resource":"card_group","id":"$mid"}""")
        assertFalse("delete(card_group) 应成功: ${del.contentJson}", del.isError)
        val delContent = mapper.readTree(del.contentJson)
        val snapshotId = delContent["snapshotId"].asText()
        val deletedChildren = delContent["deletedChildren"]
        assertEquals("回显应含 1 条 aura", 1, deletedChildren["auraBoosts"].asInt())
        assertEquals("回显应含 1 条 combo", 1, deletedChildren["comboPlans"].asInt())
        assertEquals("回显应含 2 棵私有条件树", 2, deletedChildren["privateConditionTrees"].asInt())

        // 4) 三类从属资源全部归零（此前会留孤儿行）
        assertEquals("aura 应被级联删", 0, countOf("aura_boost"))
        assertEquals("combo 应被级联删", 0, countOf("combo_plan_definition"))
        assertEquals("私有条件树应被级联删", 0, countOf("condition_tree_config"))
        assertEquals(
            "manager 应被删",
            0,
            jdbc.queryForObject("SELECT count(*) FROM card_group_manager WHERE id = ?", Int::class.java, mid)
        )
        // 自证守卫（K-TG-014 §5.4）：按库结构反查「带归属键的表」是否还有本卡组的行 ——
        // 比逐表断言更强：将来新增归属表却忘了登记为 CardGroupChild，这里会直接失败
        val residues = GlobalContext.get()
            .get<lin.repository.delete_snapshot.OrphanRowGuard>().residues(mid)
        assertTrue("级联删后不应有任何归属行残留（残留=$residues）", residues.isEmpty())

        // 5) 一键恢复：三类逐项回来（原 id + 关键字段值）
        val restore = call("restore_snapshot", """{"snapshotId":"$snapshotId"}""")
        assertFalse("restore(card_group) 应成功: ${restore.contentJson}", restore.isError)
        assertEquals("aura 应恢复", 1, countOf("aura_boost"))
        assertEquals("combo 应恢复", 1, countOf("combo_plan_definition"))
        assertEquals("私有条件树应恢复", 2, countOf("condition_tree_config"))
        assertEquals(
            "aura 原 id 保留", boostId,
            jdbc.queryForObject("SELECT id FROM aura_boost WHERE manager_id = ?", String::class.java, mid)
        )
        assertEquals(
            "combo 原 id 保留", comboId,
            jdbc.queryForObject("SELECT id FROM combo_plan_definition WHERE manager_id = ?", String::class.java, mid)
        )
        assertEquals(
            "aura name 还原", "t010_child_boost",
            jdbc.queryForObject("SELECT name FROM aura_boost WHERE id = ?", String::class.java, boostId)
        )
        assertEquals(
            "aura score 还原", 1.5,
            jdbc.queryForObject("SELECT score FROM aura_boost WHERE id = ?", Double::class.java, boostId)!!, 0.001
        )
    }

    // ─────────────────── card_pool 文件 roundtrip ───────────────────

    @Test
    fun `cardPool文件 delete然后restore重建文件`() {
        val file = Path.of("../data/cardgroup/$poolName.cardgroup")
        assertTrue("删除前文件应存在", Files.exists(file))

        val del = call("delete", """{"resource":"card_pool","id":"$poolName"}""")
        assertFalse("delete(card_pool) 应成功: ${del.contentJson}", del.isError)
        val snapshotId = mapper.readTree(del.contentJson)["snapshotId"].asText()
        assertFalse("删除后文件应不存在", Files.exists(file))
        // snapshotId 已捕获，删除后文件移除，@After 不再需要删文件（savedFile 指向已删文件，deleteIfExists 幂等）

        val restore = call("restore_snapshot", """{"snapshotId":"$snapshotId"}""")
        assertFalse("restore(card_pool) 应成功: ${restore.contentJson}", restore.isError)
        assertTrue("恢复后 .cardgroup 文件应重建", Files.exists(file))
        // cards 全回
        val cfg = lin.dao.CardGroupJsonParser.loadByFileName(poolName)
        assertNotNull("恢复后文件可解析", cfg)
        assertTrue("恢复后 cards 应非空", cfg!!.cards.isNotEmpty())
        savedFile = file // 保持 @After 清理幂等
    }

    // ─────────────────── 冲突拒绝 ───────────────────

    @Test
    fun `restore前占用原id报错且不覆盖`() {
        // 用 condition_tree 演示冲突：建一棵 → 删除 → 用同名/同 id 再建一棵占用原 id → restore 应报错
        val saveResp = call("save_condition_tree", """{"name":"t010_conflict","treeJson":${treeJsonParam()}}""")
        assertFalse(saveResp.isError)
        val ctId = mapper.readTree(saveResp.contentJson)["id"].asText()
        trackConditionTree(ctId)

        val del = call("delete", """{"resource":"condition_tree","id":"$ctId"}""")
        assertFalse(del.isError)
        val snapshotId = mapper.readTree(del.contentJson)["snapshotId"].asText()

        // 占用原 id：existingId=ctId 重新建一棵（冲突检查应在 restore 前拦下）
        val reoccupy = call(
            "save_condition_tree",
            """{"name":"t010_occupied","treeJson":${treeJsonParam()},"existingId":"$ctId"}"""
        )
        assertFalse("占用原 id 应成功: ${reoccupy.contentJson}", reoccupy.isError)

        val restore = call("restore_snapshot", """{"snapshotId":"$snapshotId"}""")
        assertTrue("原 id 已被占用，restore 应报错: ${restore.contentJson}", restore.isError)
        assertTrue(
            "错误应提示原 id 已占用",
            restore.contentJson.contains("已被现有数据占用") || restore.contentJson.contains("占用")
        )
    }

    // ─────────────────── 保留 N 条 ───────────────────

    @Test
    fun `trimTo 只保留最新N条`() {
        val repo = GlobalContext.get().get<DeleteSnapshotRepository>()
        val jdbc = GlobalContext.get().get<JdbcTemplate>()
        // 测试环境：快照表为独立产物，先清空避免与其它用例/历史残留交错，保证断言可隔离。
        jdbc.update("DELETE FROM delete_snapshot")

        val prefix = "trim_test_"
        // 插入 5 条 createdAt 递增，trimTo(3) 应只留最新 3 条
        for (i in 1..5) {
            repo.save(
                DeleteSnapshotEntity(
                    snapshotId = "$prefix$i",
                    resource = "combo_plan",
                    entityId = "e$i",
                    entityName = null,
                    payload = "{}",
                    createdAt = "2000-01-01T00:00:0$i"
                )
            )
        }
        repo.trimTo(3)
        val kept = repo.findAll().map { it.snapshotId }.filter { it.startsWith(prefix) }
        // createdAt 递增，最新 3 条 = prefix3/4/5（findAll 按 created_at 倒序）
        assertEquals("应只保留最新 3 条", listOf("$prefix" + "5", "$prefix" + "4", "$prefix" + "3"), kept)
        assertEquals("表中应仅剩 3 条", 3, repo.findAll().size)
    }

    // ─────────────────── card_group 的预设引用 + 维度项 roundtrip（T-TG-010 补账）───────────────────

    @Test
    fun `cardGroup 删除恢复后预设引用与卡组增量项一并复原`() {
        val groupRepository = GlobalContext.get().get<CardGroupRepository>()
        val presetRepository = GlobalContext.get().get<StrategyPresetRepository>()

        val saveGroup = call(
            "save_card_group",
            """{"sourceFile":"$poolName","managerName":"$managerName","bindings":[{"name":"T010守卫组","cardIds":["ICC_820"]}]}"""
        )
        assertFalse("save_card_group 应成功: ${saveGroup.contentJson}", saveGroup.isError)
        val mid = mapper.readTree(saveGroup.contentJson)["managerId"].asText()
        managerId = mid

        val savePreset = call("save_strategy_preset", """{"name":"t010_group_preset"}""")
        assertFalse("save_strategy_preset 应成功: ${savePreset.contentJson}", savePreset.isError)
        val presetId = mapper.readTree(savePreset.contentJson)["presetId"].asText()
        assertFalse(
            call("save_card_group_preset", """{"managerId":"$mid","presetId":"$presetId"}""").isError
        )
        assertFalse(
            call(
                "save_card_group_preset_delta",
                """{"managerId":"$mid","timings":[{"tagId":"CLEAN","defaultOrderWeight":4.0}]}"""
            ).isError
        )

        val del = call("delete", """{"resource":"card_group","id":"$mid"}""")
        assertFalse("delete(card_group) 应成功: ${del.contentJson}", del.isError)
        val snapshotId = mapper.readTree(del.contentJson)["snapshotId"].asText()
        assertTrue(
            "删除卡组应一并清理 CARD_GROUP 维度项（不留孤儿）",
            presetRepository.findItems(DimensionScope.CARD_GROUP, mid).isEmpty()
        )

        val restore = call("restore_snapshot", """{"snapshotId":"$snapshotId"}""")
        assertFalse("restore 应成功: ${restore.contentJson}", restore.isError)

        assertEquals(
            "恢复后 preset_id 应写回（否则「用预设」退化成「不用预设」）",
            presetId,
            groupRepository.findManagerById(mid)?.presetId
        )
        assertEquals(
            "恢复后卡组增量项应写回",
            4.0,
            presetRepository.findTimings(DimensionScope.CARD_GROUP, mid).getValue("CLEAN").defaultOrderWeight!!,
            0.0
        )

        presetRepository.deletePreset(presetId)
    }

    // ─────────────────── T-TG-020：卡组快照的预设引用不得悬空 ───────────────────

    /**
     * 可达路径：删除卡组 ⇒ 预设失去最后一个引用而被删 ⇒ 再 `restore_snapshot` 恢复卡组时，
     * 快照里的 `presetId` 已指向不存在的预设。
     *
     * 期望：**拒绝恢复**（而不是静默置空 —— 那会把"用预设"悄悄变成"不用预设"＝兜底全开，方向相反），
     * 并给出可操作指引；拒绝发生在事务前 ⇒ 不留半个卡组。
     */
    @Test
    fun `卡组快照引用的预设已不存在时拒绝恢复`() {
        val groupRepository = GlobalContext.get().get<CardGroupRepository>()
        val presetRepository = GlobalContext.get().get<StrategyPresetRepository>()
        var presetId: String? = null
        try {
            val saveGroup = call(
                "save_card_group",
                """{"sourceFile":"$poolName","managerName":"$managerName","bindings":[{"name":"T020守卫组","cardIds":["ICC_820"]}]}"""
            )
            assertFalse("save_card_group 应成功: ${saveGroup.contentJson}", saveGroup.isError)
            val mid = mapper.readTree(saveGroup.contentJson)["managerId"].asText()
            managerId = mid

            val savePreset = call("save_strategy_preset", """{"name":"t020_preset"}""")
            assertFalse("save_strategy_preset 应成功: ${savePreset.contentJson}", savePreset.isError)
            presetId = mapper.readTree(savePreset.contentJson)["presetId"].asText()
            assertFalse(
                "卡组引用预设应成功",
                call("save_card_group_preset", """{"managerId":"$mid","presetId":"$presetId"}""").isError
            )

            val del = call("delete", """{"resource":"card_group","id":"$mid"}""")
            assertFalse("delete(card_group) 应成功: ${del.contentJson}", del.isError)
            val snapshotId = mapper.readTree(del.contentJson)["snapshotId"].asText()

            // 卡组已删 ⇒ 预设失去最后一个引用 ⇒ 允许删除（这正是悬空引用的产生路径）
            presetRepository.deletePreset(presetId!!)

            val restore = call("restore_snapshot", """{"snapshotId":"$snapshotId"}""")
            assertTrue(
                "T-TG-020：快照引用的预设不存在时应拒绝恢复: ${restore.contentJson}",
                restore.isError
            )
            assertTrue(
                "拒绝文案应点出缺失的 presetId 与修法: ${restore.contentJson}",
                restore.contentJson.contains(presetId!!) && restore.contentJson.contains("restore_snapshot")
            )
            assertNull("拒绝发生在事务前，不得留下半个卡组", groupRepository.findManagerById(mid))
        } finally {
            presetId?.let { presetRepository.deletePreset(it) } // 幂等：不存在时返回 null
        }
    }

    // ─────────────────── T-TG-023：树快照的归属卡组不得悬空 ───────────────────

    /**
     * 可达路径：树**先**被单独删（落快照）⇒ 卡组**再**被删（级联删树时该树已不在库中，这条快照留了下来）
     * ⇒ 恢复该树快照时，快照里的 `managerId` 已指向不存在的卡组。
     *
     * 期望：**拒绝恢复**（而不是造出"悬空归属" —— 该树此后对任何卡组都不输出，只在日志留 warn）。
     */
    @Test
    fun `树快照的归属卡组已不存在时拒绝恢复`() {
        val groupRepository = GlobalContext.get().get<CardGroupRepository>()
        val treeRepository = GlobalContext.get().get<lin.repository.tree_config.TreeConfigRepository>()
        val deckId = "T023_DECK"
        val tid = "T023_TREE"
        try {
            groupRepository.saveManager(
                lin.repository.card_group.CardManagerEntity(
                    id = deckId, name = deckId, sourceFile = "$deckId.cardgroup", enabled = true
                )
            )
            treeRepository.save(
                lin.repository.tree_config.TreeConfigEntity(
                    id = tid,
                    bindingType = lin.rule.tree.EvaluatorTreeBindingType.PURPOSE_TAG.name,
                    bindingIds = "CLEAN",
                    name = tid,
                    configData = """{"root":{"Leaf":{"payload":{"Rule":{"nodeId":"r1"}}}}}""",
                    enabled = true,
                    managerId = deckId
                )
            )

            val del = call("delete", """{"resource":"evaluator_tree","id":"$tid"}""")
            assertFalse("delete(evaluator_tree) 应成功: ${del.contentJson}", del.isError)
            val snapshotId = mapper.readTree(del.contentJson)["snapshotId"].asText()

            // 卡组被删（级联删树时该树已不在库中 ⇒ 这条树快照留了下来，归属随之悬空）
            groupRepository.deleteManager(deckId)

            val restore = call("restore_snapshot", """{"snapshotId":"$snapshotId"}""")
            assertTrue("T-TG-023：归属卡组不存在时应拒绝恢复: ${restore.contentJson}", restore.isError)
            assertTrue(
                "拒绝文案应点出缺失的归属卡组: ${restore.contentJson}",
                restore.contentJson.contains(deckId)
            )
            assertNull("拒绝发生在写库前，树不应被恢复", treeRepository.findById(tid))
        } finally {
            treeRepository.deleteById(tid)
            groupRepository.deleteManager(deckId)
        }
    }
}
