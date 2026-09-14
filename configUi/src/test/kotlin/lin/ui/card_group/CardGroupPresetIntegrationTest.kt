package lin.ui.card_group

import lin.mcp.McpTestEnv
import lin.repository.card_group.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.koin.core.context.GlobalContext

/**
 * T-TG-018：卡组工作台接入策略预设与增量项微调集成测试。
 */
class CardGroupPresetIntegrationTest : McpTestEnv() {

    private val cardGroupService: CardGroupService by lazy {
        GlobalContext.get().get<CardGroupService>()
    }
    private val presetService: StrategyPresetService by lazy {
        GlobalContext.get().get<StrategyPresetService>()
    }
    private val catalogLoader: lin.ui.service.PresetCatalogLoader by lazy {
        GlobalContext.get().get<lin.ui.service.PresetCatalogLoader>()
    }

    private val treeRepository: lin.repository.tree_config.TreeConfigRepository by lazy {
        GlobalContext.get().get()
    }

    private lateinit var store: WorkbenchStore
    private val createdManagerIds = mutableListOf<String>()
    private val createdPresetIds = mutableListOf<String>()
    private val createdTreeIds = mutableListOf<String>()

    @Before
    fun setUp() {
        store = WorkbenchStore(cardGroupService, presetService, catalogLoader)
    }

    @After
    fun tearDown() {
        createdManagerIds.forEach { cardGroupService.deleteManager(it) }
        createdPresetIds.forEach { presetService.deletePreset(it) }
        createdTreeIds.forEach { treeRepository.deleteById(it) }
    }

    private fun insertGlobalTree(id: String, tags: List<String>): String {
        treeRepository.save(
            lin.repository.tree_config.TreeConfigEntity(
                id = id,
                bindingType = "PURPOSE_TAG",
                bindingIds = tags.joinToString(","),
                name = id,
                configData = """{"root":{"Leaf":{"payload":{"Rule":{"nodeId":"r1"}}}}}""",
                enabled = true,
                managerId = null
            )
        )
        createdTreeIds.add(id)
        return id
    }

    private fun createPreset(name: String): String {
        val res = presetService.savePreset(
            presetId = null,
            name = name,
            description = "测试预设描述",
            treeSelections = mapOf("DRAW" to listOf("tree-1")),
            timings = mapOf("DRAW" to TimingOverride(defaultStage = "EARLY"))
        )
        assertNotNull(res)
        val id = res!!.presetId
        createdPresetIds.add(id)
        return id
    }

    private fun createManager(name: String, presetId: String? = null): String {
        val id = cardGroupService.saveManager(
            ManagerSaveCommand(
                name = name,
                sourceFile = "test.cardgroup",
                enabled = true,
                bindings = emptyList()
            )
        )
        createdManagerIds.add(id)
        if (presetId != null) {
            presetService.setCardGroupPreset(id, presetId)
        }
        return id
    }

    @Test
    fun testStoreInitialDataLoadsPresetsAndUniverse() {
        insertGlobalTree("TG_CG_TREE_DRAW", listOf("DRAW"))
        val presetId = createPreset("预设-初始加载")
        store.loadInitialData()

        val state = store.state
        assertTrue("应加载可用预设列表", state.availablePresets.any { it.preset.id == presetId })
        assertTrue("用途全集不应为空", state.purposeUniverse.contains("DRAW"))
        assertTrue("时序规则不应为空", state.timingRules.isNotEmpty())
    }

    @Test
    fun testSelectManagerLoadsPresetDetailAndDeckDelta() {
        val presetId = createPreset("预设-选中测试")
        val managerId = createManager("卡组-预设加载", presetId)

        // 设置增量项
        presetService.saveDeckDelta(
            managerId = managerId,
            treeExclusions = mapOf("DRAW" to listOf("tree-exclude-1")),
            timings = mapOf("DRAW" to TimingOverride(defaultStage = "LATE"))
        )

        store.loadInitialData()
        val item = store.state.managers.find { it.entity?.id == managerId }
        assertNotNull("卡组列表中应包含新建卡组", item)

        store.selectManager(item)
        val state = store.state

        assertEquals("选中的卡组预设 ID 应匹配", presetId, state.managerPresetId)
        assertNotNull("应加载预设详情", state.currentPresetDetail)
        assertEquals("预设详情名称匹配", "预设-选中测试", state.currentPresetDetail?.preset?.name)

        assertNotNull("应加载卡组增量项", state.currentDeckDelta)
        assertEquals(listOf("tree-exclude-1"), state.currentDeckDelta?.treeExclusions?.get("DRAW")?.toList())
        assertEquals("LATE", state.currentDeckDelta?.timings?.get("DRAW")?.defaultStage)
    }

    @Test
    fun testSetCardGroupPresetSwitchAndClear() {
        val presetId1 = createPreset("预设-1")
        val presetId2 = createPreset("预设-2")
        val managerId = createManager("卡组-切换测试", presetId1)

        store.loadInitialData()
        val item = store.state.managers.find { it.entity?.id == managerId }
        store.selectManager(item)

        // 1. 切换到预设 2
        val err1 = store.setCardGroupPreset(presetId2)
        assertNull("切换预设成功", err1)
        assertEquals(presetId2, store.state.managerPresetId)
        assertEquals("预设-2", store.state.currentPresetDetail?.preset?.name)

        // 验证数据库已持久化
        val reloaded1 = cardGroupService.loadAllManagers().find { it.id == managerId }
        assertEquals("DB 中 preset_id 应更新为 presetId2", presetId2, reloaded1?.presetId)

        // 2. 清除预设（不用预设）
        val err2 = store.setCardGroupPreset(null)
        assertNull("清除预设成功", err2)
        assertNull("state.managerPresetId 应为 null", store.state.managerPresetId)
        assertNull("state.currentPresetDetail 应为 null", store.state.currentPresetDetail)

        // 验证数据库已清除
        val reloaded2 = cardGroupService.loadAllManagers().find { it.id == managerId }
        assertNull("DB 中 preset_id 应清空", reloaded2?.presetId)
    }

    @Test
    fun testSaveDeckDeltaPersistence() {
        val managerId = createManager("卡组-增量项测试")

        store.loadInitialData()
        val item = store.state.managers.find { it.entity?.id == managerId }
        store.selectManager(item)

        // 保存排除树与时序覆盖微调
        val exclusions = mapOf("SURPLUS" to listOf("tree-surplus-1"))
        val timings = mapOf(
            "SURPLUS" to TimingOverride(
                defaultStage = "TURN_END",
                defaultOrderWeight = 150.0,
                surplusIdleThreshold = ThresholdPatch(5),
                defaultReplanAfterUse = true
            )
        )

        val err = store.saveDeckDelta(exclusions, timings)
        assertNull("保存卡组增量项成功", err)

        val updatedDelta = store.state.currentDeckDelta
        assertNotNull(updatedDelta)
        assertEquals(listOf("tree-surplus-1"), updatedDelta?.treeExclusions?.get("SURPLUS")?.toList())
        val timing = updatedDelta?.timings?.get("SURPLUS")
        assertEquals("TURN_END", timing?.defaultStage)
        assertEquals(150.0, timing?.defaultOrderWeight)
        assertEquals(5, timing?.surplusIdleThreshold?.value)
        assertEquals(true, timing?.defaultReplanAfterUse)

        // 从 DB 直接读回验证
        val fromDb = presetService.findDeckDelta(managerId)
        assertEquals(listOf("tree-surplus-1"), fromDb.treeExclusions["SURPLUS"]?.toList())
        assertEquals("TURN_END", fromDb.timings["SURPLUS"]?.defaultStage)
    }

    @Test
    fun testDraftManagerSaveWithPreset() {
        val presetId = createPreset("预设-草稿关联")
        store.loadInitialData()

        // 新建草稿
        store.createNewManager("draft_test.cardgroup", "草稿卡组")
        assertTrue(store.state.selectedManagerItem?.isDraft == true)

        // 在草稿状态下选择预设
        store.setCardGroupPreset(presetId)
        assertEquals(presetId, store.state.managerPresetId)

        // 保存草稿
        store.saveCurrentManager()

        val savedItem = store.state.selectedManagerItem
        assertNotNull(savedItem)
        assertFalse(savedItem?.isDraft == true)
        val savedId = savedItem?.entity?.id
        assertNotNull(savedId)
        createdManagerIds.add(savedId!!)

        val managerFromDb = cardGroupService.loadAllManagers().find { it.id == savedId }
        assertEquals("保存后草稿的 presetId 应成功写入 DB", presetId, managerFromDb?.presetId)
    }
}
