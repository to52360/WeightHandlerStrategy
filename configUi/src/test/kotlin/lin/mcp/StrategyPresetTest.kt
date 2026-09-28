package lin.mcp

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import lin.bean.usePlan.UseStage
import lin.mcp.action.GetInput
import lin.mcp.action.ListInput
import lin.repository.card_group.*
import lin.repository.tree_config.TreeConfigEntity
import lin.repository.tree_config.TreeConfigRepository
import lin.serviceLoader.provider.PurposeTagIntentRuleProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext

/**
 * T-TG-015：用途预设（兜底层）+ 卡组增量项（③微调层）。
 *
 * 覆盖：
 * ① 预设时序覆盖生效、未声明字段回落全局；
 * ② 消费方增量项**逐字段压过**预设；
 * ③ 不引用预设 → 完全走全局（默认状态，零开关）；清除引用即回落；
 * ④ **K-TG-005 关闭**：`clearSurplusIdleThreshold` 能把 N 覆盖为「无门槛」；
 * ⑤ 前置校验：树必须存在、必须绑了该用途、时序只能覆盖有全局规则行的用途；
 * ⑥ **未声明禁用**：空预设 ⇒ 全部用途树被禁，`disabledPurposes` 回报。
 *
 * ⚠️ 树裁剪的**合并规则**在 `DimensionItemResolverTest`（纯函数）覆盖；
 * 本类只在 MCP 层验语义与校验（不构造 `EvaluatorTreeConfig`）。
 */
class StrategyPresetTest : McpTestEnv() {

    private val presetService: StrategyPresetService by lazy {
        GlobalContext.get().get<StrategyPresetService>()
    }
    private val presetRepository: StrategyPresetRepository by lazy {
        GlobalContext.get().get<StrategyPresetRepository>()
    }
    private val ruleProvider: PurposeTagIntentRuleProvider by lazy {
        GlobalContext.get().get<PurposeTagIntentRuleProvider>()
    }
    private val groupRepository: CardGroupRepository by lazy {
        GlobalContext.get().get<CardGroupRepository>()
    }
    private val treeRepository: TreeConfigRepository by lazy {
        GlobalContext.get().get<TreeConfigRepository>()
    }

    private var testDeckId: String? = null
    private var presetId: String? = null
    private val createdTreeIds = mutableListOf<String>()

    @After
    fun cleanUpPreset() {
        presetId?.let { presetService.deletePreset(it) }
        createdTreeIds.forEach { treeRepository.deleteById(it) }
        testDeckId?.let {
            presetRepository.deleteItems(DimensionScope.CARD_GROUP, it)
            groupRepository.deleteManager(it)
        }
    }

    /**
     * 建一个 enabled 卡组 —— 直接走 repository（**有意绕开** `CardGroupService.saveManager`：
     * 后者在 enabled 时会自动禁用其余卡组，测试不应污染其他卡组的启用状态）。
     */
    private fun createEnabledDeck(id: String): String {
        groupRepository.saveManager(
            CardManagerEntity(id = id, name = id, sourceFile = "$id.cardgroup", enabled = true)
        )
        return id
    }

    /** 直接落一行用途树（只用到列，不需要合法 `config_data` —— 本类不做反序列化）。 */
    private fun insertPurposeTree(id: String, tags: List<String>): String {
        treeRepository.save(
            TreeConfigEntity(
                id = id,
                bindingType = "PURPOSE_TAG",
                bindingIds = tags.joinToString(","),
                name = id,
                configData = """{"root":{"Leaf":{"payload":{"Rule":{"nodeId":"r1"}}}}}""",
                enabled = true
            )
        )
        createdTreeIds += id
        return id
    }

    private fun savePreset(json: String): String {
        val result = call("save_strategy_preset", json)
        assertEquals("保存预设应成功: ${result.contentJson}", false, result.isError)
        val id = mapper.readTree(result.contentJson).get("presetId").asText()
        presetId = id
        return id
    }

    private fun rulesByTag() = ruleProvider.rules().associateBy { it.tagId.value }

    // ─────────────────────── 时序 ───────────────────────

    @Test
    fun `预设声明用途即产生规则且未声明字段回落全局行`() {
        val deck = createEnabledDeck("TG_PRESET_DECK_A")
        testDeckId = deck

        // 全局基线：CLEAN = MID / N=1（现仅作「缺省值来源」）
        val id = savePreset("""{"name":"TG_PRESET_A","timings":[{"tagId":"CLEAN","defaultStage":"LATE"}]}""")
        assertEquals(
            false,
            call("save_card_group_preset", """{"managerId":"$deck","presetId":"$id"}""").isError
        )

        val clean = rulesByTag().getValue("CLEAN")
        assertEquals("预设声明的 stage 应生效", UseStage.LATE, clean.defaultStage)
        assertEquals("声明里没写的 N 应回落全局行 1", 1, clean.defaultSurplusIdleThreshold)
        assertEquals("声明里没写的 priority 应回落全局行 300", 300, clean.priority)
    }

    @Test
    fun `未声明的用途不再有规则`() {
        val deck = createEnabledDeck("TG_PRESET_DECK_UNKNOWN")
        testDeckId = deck

        // 只声明 CLEAN ⇒ GREED / SAVE_LIFE 等一律无规则（D-TG-018：未声明 = 无规则）
        val id = savePreset("""{"name":"TG_PRESET_UNKNOWN","timings":[{"tagId":"CLEAN","defaultStage":"LATE"}]}""")
        call("save_card_group_preset", """{"managerId":"$deck","presetId":"$id"}""")

        assertEquals("只应剩 CLEAN 一条规则", setOf("CLEAN"), rulesByTag().keys)
    }

    @Test
    fun `消费方增量项逐字段压过预设`() {
        val deck = createEnabledDeck("TG_PRESET_DECK_A2")
        testDeckId = deck

        val id = savePreset("""{"name":"TG_PRESET_A2","timings":[{"tagId":"CLEAN","defaultStage":"LATE"}]}""")
        call("save_card_group_preset", """{"managerId":"$deck","presetId":"$id"}""")
        assertEquals(UseStage.LATE, rulesByTag().getValue("CLEAN").defaultStage)

        // 卡组只声明 stage → 压过预设
        val delta = call(
            "save_card_group_preset_delta",
            """{"managerId":"$deck","timings":[{"tagId":"CLEAN","defaultStage":"GENERAL"}]}"""
        )
        assertEquals(false, delta.isError)
        assertEquals(UseStage.GENERAL, rulesByTag().getValue("CLEAN").defaultStage)
        assertEquals("未声明的 N 仍回落全局行", 1, rulesByTag().getValue("CLEAN").defaultSurplusIdleThreshold)
    }

    /** 消费侧「去除」通道：`excludePurposes` 把该用途从本卡组规则集合里减掉（≠ 回落全局默认值）。 */
    @Test
    fun `消费侧可排除预设声明的用途`() {
        val deck = createEnabledDeck("TG_PRESET_DECK_EXCL")
        testDeckId = deck

        val id = savePreset("""{"name":"TG_PRESET_EXCL","timings":[{"tagId":"CLEAN","defaultStage":"LATE"}]}""")
        call("save_card_group_preset", """{"managerId":"$deck","presetId":"$id"}""")
        assertEquals(setOf("CLEAN"), rulesByTag().keys)

        val excluded = call(
            "save_card_group_preset_delta",
            """{"managerId":"$deck","excludePurposes":["CLEAN"]}"""
        )
        assertEquals(false, excluded.isError)
        assertTrue("被排除的用途不应有规则（且不回落全局默认值）", rulesByTag().isEmpty())

        val restored = call(
            "save_card_group_preset_delta",
            """{"managerId":"$deck","excludePurposes":[]}"""
        )
        assertEquals(false, restored.isError)
        assertEquals("清空排除后应恢复按预设声明生效", setOf("CLEAN"), rulesByTag().keys)
    }

    /** 排除项同样受「只有内置作用」边界约束（与 timings / surplus 一致）。 */
    @Test
    fun `清单外用途不能被排除`() {
        val deck = createEnabledDeck("TG_PRESET_DECK_EXCL2")
        testDeckId = deck
        val r = call(
            "save_card_group_preset_delta",
            """{"managerId":"$deck","excludePurposes":["FINISH"]}"""
        )
        assertTrue("清单外标记不应被接受为排除项", r.isError)
    }

    /** T-TG-039：空声明元素（一个字段都不给）必须报错，不能静默丢弃。 */
    @Test
    fun `空声明元素被拒而不是静默丢弃`() {
        val r1 = call("save_strategy_preset", """{"name":"TG_PRESET_EMPTY_T","timings":[{"tagId":"CLEAN"}]}""")
        assertTrue("timings 空元素应报错: ${r1.contentJson}", r1.isError)
        val r2 = call("save_strategy_preset", """{"name":"TG_PRESET_EMPTY_S","surplus":[{"tagId":"CLEAN"}]}""")
        assertTrue("surplus 空元素应报错: ${r2.contentJson}", r2.isError)
    }

    @Test
    fun `消费方增量项可声明预设没声明的用途`() {
        val deck = createEnabledDeck("TG_PRESET_DECK_ADD")
        testDeckId = deck

        val id = savePreset("""{"name":"TG_PRESET_ADD","timings":[{"tagId":"CLEAN","defaultStage":"LATE"}]}""")
        call("save_card_group_preset", """{"managerId":"$deck","presetId":"$id"}""")
        assertEquals(setOf("CLEAN"), rulesByTag().keys)

        // Q-TG-009 的"消费侧加项"在声明模型下自然成立：增量项自行声明一个新用途
        val delta = call(
            "save_card_group_preset_delta",
            """{"managerId":"$deck","timings":[{"tagId":"GREED","defaultStage":"SETUP"}]}"""
        )
        assertEquals(false, delta.isError)
        assertEquals("增量项声明的用途也应产出规则", setOf("CLEAN", "GREED"), rulesByTag().keys)
        assertEquals(UseStage.SETUP, rulesByTag().getValue("GREED").defaultStage)
    }

    @Test
    fun `priority 可在声明里可选覆盖`() {
        val deck = createEnabledDeck("TG_PRESET_DECK_PRIO")
        testDeckId = deck

        val id = savePreset(
            """{"name":"TG_PRESET_PRIO","timings":[{"tagId":"CLEAN","defaultStage":"MID","priority":777}]}"""
        )
        call("save_card_group_preset", """{"managerId":"$deck","presetId":"$id"}""")
        assertEquals("声明的 priority 应生效", 777, rulesByTag().getValue("CLEAN").priority)

        // 读回也应带上 priority
        val got = call("get", """{"resource":"strategy_preset","id":"$id"}""")
        assertEquals(777, mapper.readTree(got.contentJson).get("timings").get(0).get("priority").asInt())
    }

    /**
     * K-TG-005（T-TG-029 后的契约）：惜售门槛走**独立的 `surplus` 参数**，
     * `clearSurplusIdleThreshold` 可把 N 声明为「不设门槛」（显式 null ≠ 未声明）。
     */
    @Test
    fun `K-TG-005 可把用途的 N 声明为无门槛`() {
        val deck = createEnabledDeck("TG_PRESET_DECK_A3")
        testDeckId = deck

        val id = savePreset(
            """{"name":"TG_PRESET_A3","timings":[{"tagId":"CLEAN","defaultStage":"LATE"}],
                "surplus":[{"tagId":"CLEAN","clearSurplusIdleThreshold":true}]}"""
        )
        call("save_card_group_preset", """{"managerId":"$deck","presetId":"$id"}""")
        assertNull(
            "clearSurplusIdleThreshold 应把 N 声明为「不设门槛」",
            rulesByTag().getValue("CLEAN").defaultSurplusIdleThreshold
        )
    }

    /** T-TG-029：`surplus` 声明值应生效，且未声明惜售的用途回落全局行 N。 */
    @Test
    fun `惜售声明值生效且未声明时回落全局行`() {
        val deck = createEnabledDeck("TG_PRESET_DECK_S1")
        testDeckId = deck

        val id = savePreset(
            """{"name":"TG_PRESET_S1","surplus":[{"tagId":"GREED","defaultSurplusIdleThreshold":4}]}"""
        )
        call("save_card_group_preset", """{"managerId":"$deck","presetId":"$id"}""")
        assertEquals("指定的惜售门槛应生效", 4, rulesByTag().getValue("GREED").defaultSurplusIdleThreshold)
    }

    @Test
    fun `N 与清除开关互斥`() {
        val r = call(
            "save_strategy_preset",
            """{"name":"TG_PRESET_A4","surplus":[{"tagId":"CLEAN","defaultSurplusIdleThreshold":2,"clearSurplusIdleThreshold":true}]}"""
        )
        assertTrue("同时传 N 与清除开关应报错", r.isError)
    }

    @Test
    fun `不引用预设时无任何时序规则`() {
        createEnabledDeck("TG_PRESET_DECK_B").also { testDeckId = it }

        savePreset("""{"name":"TG_PRESET_B","timings":[{"tagId":"CLEAN","defaultStage":"LATE"}]}""")
        // 建了预设但**不引用** ⇒ 无声明 ⇒ 空规则集（D-TG-018：合法终态，无隐式作用）
        assertTrue("不引用预设 ⇒ 不得有任何时序规则", rulesByTag().isEmpty())
    }

    @Test
    fun `清除引用后规则集清空`() {
        val deck = createEnabledDeck("TG_PRESET_DECK_C")
        testDeckId = deck

        val id = savePreset("""{"name":"TG_PRESET_C","timings":[{"tagId":"CLEAN","defaultStage":"LATE"}]}""")
        call("save_card_group_preset", """{"managerId":"$deck","presetId":"$id"}""")
        assertEquals(UseStage.LATE, rulesByTag().getValue("CLEAN").defaultStage)

        assertEquals(false, call("save_card_group_preset", """{"managerId":"$deck"}""").isError)
        assertTrue("解除引用 ⇒ 回到「无声明」⇒ 规则集清空", rulesByTag().isEmpty())
    }

    // ─────────────────────── 校验 ───────────────────────

    /**
     * T-TG-038 / D-TG-019 **收窄**：可声明者 = 内置作用清单（现 5 个），**不是**"有全局规则行"。
     *
     * `FINISH` 无编排行为（Q-033：斩杀是局面属性）⇒ 声明时序应被拒。
     * 注：T-TG-028 曾放开"任意 tagId 可声明"，本用例是那次放开的**反向收回**；
     * 若失败被拒则不会落库，故无需 savePreset 助手登记清理。
     */
    @Test
    fun `清单外的标记不能被声明为时序`() {
        val r = call(
            "save_strategy_preset",
            """{"name":"TG_PRESET_D","timings":[{"tagId":"FINISH","defaultStage":"LAST","priority":900}]}"""
        )
        assertTrue("清单外标记（无编排行为）的时序声明应被拒绝", r.isError)
        assertTrue(
            "错误信息应回显被拒的 tagId 与可选清单: ${r.contentJson}",
            r.contentJson.contains("FINISH") && r.contentJson.contains("CLEAN")
        )
    }

    /** 同上，惜售维度（独立入参）同样受"只有内置作用可声明"约束。 */
    @Test
    fun `清单外的标记不能被声明为惜售门槛`() {
        val r = call(
            "save_strategy_preset",
            """{"name":"TG_PRESET_D2","surplus":[{"tagId":"FINISH","defaultSurplusIdleThreshold":2}]}"""
        )
        assertTrue("清单外标记的惜售声明应被拒绝", r.isError)
    }

    @Test
    fun `用途树不存在被拒`() {
        val r = call(
            "save_strategy_preset",
            """{"name":"TG_PRESET_E","treeSelections":[{"tagId":"CLEAN","treeIds":["no_such_tree"]}]}"""
        )
        assertTrue("不存在的树应被拒", r.isError)
    }

    @Test
    fun `没绑该用途的树被拒`() {
        val tree = insertPurposeTree("TG_TREE_BOUND_CLEAN", listOf("CLEAN"))
        val r = call(
            "save_strategy_preset",
            """{"name":"TG_PRESET_F","treeSelections":[{"tagId":"GREED","treeIds":["$tree"]}]}"""
        )
        assertTrue("只能选绑了该用途的树", r.isError)
    }

    // ─────────────────────── 未声明禁用 + 增量项读写 ───────────────────────

    @Test
    fun `空预设禁用全部用途树并回报禁用清单`() {
        val tree = insertPurposeTree("TG_TREE_CLEAN_1", listOf("CLEAN"))
        val deck = createEnabledDeck("TG_PRESET_DECK_G")
        testDeckId = deck

        // ① 空预设 ⇒ CLEAN 被禁
        val emptyId = savePreset("""{"name":"TG_PRESET_G_EMPTY"}""")
        val emptyResp = call("save_strategy_preset", """{"presetId":"$emptyId","name":"TG_PRESET_G_EMPTY"}""")
        assertTrue(
            "空预设应把库中已有用途树的用途列为被禁用",
            mapper.readTree(emptyResp.contentJson).get("disabledPurposes").any { it.asText() == "CLEAN" }
        )

        // ② 声明该用途保留这棵树 ⇒ 不再被禁
        val keptResp = call(
            "save_strategy_preset",
            """{"presetId":"$emptyId","name":"TG_PRESET_G_EMPTY","treeSelections":[{"tagId":"CLEAN","treeIds":["$tree"]}]}"""
        )
        assertEquals(false, keptResp.isError)
        assertFalse(
            "已声明的用途不应出现在禁用清单",
            mapper.readTree(keptResp.contentJson).get("disabledPurposes").any { it.asText() == "CLEAN" }
        )

        // ③ get 能读回生效项
        val got = call("get", """{"resource":"strategy_preset","id":"$emptyId"}""")
        assertEquals(false, got.isError)
        val treeSelections = mapper.readTree(got.contentJson).get("treeSelections")
        assertEquals("CLEAN", treeSelections.get(0).get("tagId").asText())
        assertEquals(tree, treeSelections.get(0).get("treeIds").get(0).asText())
    }

    @Test
    fun `卡组增量项可写入并读回`() {
        val tree = insertPurposeTree("TG_TREE_CLEAN_2", listOf("CLEAN"))
        val deck = createEnabledDeck("TG_PRESET_DECK_H")
        testDeckId = deck

        val delta = call(
            "save_card_group_preset_delta",
            """
            {"managerId":"$deck",
             "excludeTreeSelections":[{"tagId":"CLEAN","treeIds":["$tree"]}],
             "timings":[{"tagId":"CLEAN","defaultOrderWeight":3.0}]}
            """.trimIndent()
        )
        assertEquals(false, delta.isError)

        val saved = presetService.findDeckDelta(deck)
        assertEquals(setOf(tree), saved.treeExclusions["CLEAN"])
        assertEquals(3.0, saved.timings.getValue("CLEAN").defaultOrderWeight!!, 0.0)

        // 空数组 = 清空
        val cleared = call(
            "save_card_group_preset_delta",
            """{"managerId":"$deck","excludeTreeSelections":[],"timings":[]}"""
        )
        assertEquals(false, cleared.isError)
        val after = presetService.findDeckDelta(deck)
        assertTrue(after.treeExclusions.isEmpty())
        assertTrue(after.timings.isEmpty())
    }

    @Test
    fun `更新预设只传 timings 时 disabledPurposes 不误报存量声明`() {
        val tree = insertPurposeTree("TG_TREE_CLEAN_3", listOf("CLEAN"))
        val deck = createEnabledDeck("TG_PRESET_DECK_J")
        testDeckId = deck

        // 首次保存：声明 CLEAN 保留该树
        val id = savePreset(
            """{"name":"TG_PRESET_J","treeSelections":[{"tagId":"CLEAN","treeIds":["$tree"]}]}"""
        )

        // 更新：只传 timings（treeSelections 省略 = 不改）⇒ disabledPurposes 不得把 CLEAN 误报为被禁
        val updated = call(
            "save_strategy_preset",
            """{"presetId":"$id","name":"TG_PRESET_J","timings":[{"tagId":"CLEAN","defaultStage":"LATE"}]}"""
        )
        assertEquals(false, updated.isError)
        assertFalse(
            "存量树声明未被本次输入携带，不应误报为禁用",
            mapper.readTree(updated.contentJson).get("disabledPurposes").any { it.asText() == "CLEAN" }
        )
    }

    @Test
    fun `预设被禁用用途看板不把卡组专属树的用途算作被禁用(D-TG-015与T-TG-024)`() {
        val globalTree = insertPurposeTree("TG_TREE_GLOBAL_CLEAN", listOf("CLEAN"))
        val deckTreeId = "TG_TREE_DECK_BURST"
        treeRepository.save(
            TreeConfigEntity(
                id = deckTreeId,
                bindingType = "PURPOSE_TAG",
                bindingIds = "BURST",
                name = deckTreeId,
                configData = """{"root":{"Leaf":{"payload":{"Rule":{"nodeId":"r1"}}}}}""",
                enabled = true,
                managerId = "SOME_DECK_XYZ"
            )
        )
        createdTreeIds += deckTreeId

        val id = savePreset(
            """
            {"name":"TG_PRESET_UNIVERSE",
             "treeSelections":[{"tagId":"CLEAN","treeIds":["$globalTree"]}]}
            """.trimIndent()
        )
        val got = call("get", """{"resource":"strategy_preset","id":"$id"}""")
        assertEquals(false, got.isError)
        val detail = mapper.readTree(got.contentJson)
        val disabledPurposes = detail.get("disabledPurposes").map { it.asText() }
        assertFalse("卡组专属用途树的用途不应出现在预设被禁用看板: $disabledPurposes", "BURST" in disabledPurposes)
    }

    // ─────────────────────── 删除通道（T-TG-010 / D-TG-010）───────────────────────

    @Test
    fun `预设被卡组引用时拒绝删除并回显引用卡组`() {
        val deck = createEnabledDeck("TG_PRESET_DECK_DEL")
        testDeckId = deck
        val id = savePreset("""{"name":"TG_PRESET_DEL_REF"}""")
        assertEquals(false, call("save_card_group_preset", """{"managerId":"$deck","presetId":"$id"}""").isError)

        val refused = call("delete", """{"resource":"strategy_preset","id":"$id"}""")
        assertTrue("引用中删除应被拒绝: ${refused.contentJson}", refused.isError)
        assertTrue("拒绝文案应回显引用卡组名", refused.contentJson.contains(deck))
        assertEquals(
            "拒绝删除后预设应仍在",
            false,
            call("get", """{"resource":"strategy_preset","id":"$id"}""").isError
        )

        // 解除引用（不带 presetId = 不用预设）后可删
        assertEquals(false, call("save_card_group_preset", """{"managerId":"$deck"}""").isError)
        val deleted = call("delete", """{"resource":"strategy_preset","id":"$id"}""")
        assertEquals("解除引用后删除应成功: ${deleted.contentJson}", false, deleted.isError)
        assertEquals(id, mapper.readTree(deleted.contentJson).get("deleted").asText())
    }

    @Test
    fun `预设删除落快照并可恢复含两个维度项`() {
        val tree = insertPurposeTree("TG_TREE_CLEAN_4", listOf("CLEAN"))
        val deck = createEnabledDeck("TG_PRESET_DECK_RESTORE")
        testDeckId = deck
        val id = savePreset(
            """
            {"name":"TG_PRESET_RESTORE",
             "treeSelections":[{"tagId":"CLEAN","treeIds":["$tree"]}],
             "timings":[{"tagId":"CLEAN","defaultOrderWeight":2.5}]}
            """.trimIndent()
        )

        val deleted = call("delete", """{"resource":"strategy_preset","id":"$id"}""")
        assertEquals("删除应成功: ${deleted.contentJson}", false, deleted.isError)
        val snapshotId = mapper.readTree(deleted.contentJson).get("snapshotId").asText()
        assertTrue("删除后应查不到", call("get", """{"resource":"strategy_preset","id":"$id"}""").isError)

        val restored = call("restore_snapshot", """{"snapshotId":"$snapshotId"}""")
        assertEquals("恢复应成功: ${restored.contentJson}", false, restored.isError)

        val got = call("get", """{"resource":"strategy_preset","id":"$id"}""")
        assertEquals(false, got.isError)
        val detail = mapper.readTree(got.contentJson)
        assertEquals("CLEAN", detail.get("treeSelections").get(0).get("tagId").asText())
        assertEquals(tree, detail.get("treeSelections").get(0).get("treeIds").get(0).asText())
        assertEquals(2.5, detail.get("timings").get(0).get("defaultOrderWeight").asDouble(), 0.0)
    }

    @Test
    fun `list 回报引用者可识别卡组专用预设`() {
        val deck = createEnabledDeck("TG_PRESET_DECK_I")
        testDeckId = deck
        val id = savePreset("""{"name":"TG_PRESET_I"}""")
        call("save_card_group_preset", """{"managerId":"$deck","presetId":"$id"}""")

        val listed = call("list", """{"resource":"strategy_preset"}""")
        assertEquals(false, listed.isError)
        val mine = mapper.readTree(listed.contentJson).first { it.get("id").asText() == id }
        assertNotNull(mine)
        assertEquals(deck, mine.get("referencedBy").get(0).get("managerId").asText())
    }

    /**
     * 防漂移（描述泛化后）：get/list 的支持面不再枚举在入参描述里，改由**注册表动态产出**、
     * 经 tool_capabilities 查询 ⇒ 本用例断言两件事：
     * ① 支持面本体（tool_capabilities 支持矩阵）里 get/list 都含 strategy_preset（注册面没丢）；
     * ② get/list 的入参描述确实指向 tool_capabilities（描述与查询入口未失联）。
     */
    @Test
    fun `get 与 list 的支持面含 strategy_preset 且描述指向查询入口`() {
        val matrix = call("tool_capabilities")
        assertEquals(false, matrix.isError)
        val caps = mapper.readTree(matrix.contentJson)
        assertTrue(
            "支持矩阵 get 应含 strategy_preset: ${caps.get("get")}",
            caps.get("get").any { it.asText() == "strategy_preset" }
        )
        assertTrue(
            "支持矩阵 list 应含 strategy_preset: ${caps.get("list")}",
            caps.get("list").any { it.asText() == "strategy_preset" }
        )

        fun resourceDesc(cls: Class<*>) =
            cls.getDeclaredField("resource").getAnnotation(JsonPropertyDescription::class.java).value
        assertTrue(
            "GetInput 描述应指向 tool_capabilities（支持面以查询为准）",
            resourceDesc(GetInput::class.java).contains("tool_capabilities")
        )
        assertTrue(
            "ListInput 描述应指向 tool_capabilities（支持面以查询为准）",
            resourceDesc(ListInput::class.java).contains("tool_capabilities")
        )
    }
}
