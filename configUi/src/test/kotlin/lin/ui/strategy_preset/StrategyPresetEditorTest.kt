package lin.ui.strategy_preset

import lin.mcp.McpTestEnv
import lin.repository.card_group.StrategyPresetService
import lin.repository.card_group.ThresholdPatch
import lin.repository.card_group.TimingOverride
import lin.repository.tree_config.TreeConfigEntity
import lin.repository.tree_config.TreeConfigRepository
import lin.ui.card_group.ActiveManagerHolder
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.koin.core.context.GlobalContext

/**
 * T-TG-017：策略预设白名单与时序编辑器集成测试。
 */
class StrategyPresetEditorTest : McpTestEnv() {

    private val service: StrategyPresetService by lazy {
        GlobalContext.get().get<StrategyPresetService>()
    }
    private val treeRepository: TreeConfigRepository by lazy {
        GlobalContext.get().get<TreeConfigRepository>()
    }
    private val activeManagerHolder: ActiveManagerHolder by lazy {
        GlobalContext.get().get<ActiveManagerHolder>()
    }
    private val catalogLoader: lin.ui.service.PresetCatalogLoader by lazy {
        GlobalContext.get().get<lin.ui.service.PresetCatalogLoader>()
    }

    private lateinit var store: StrategyPresetStore
    private val createdPresetIds = mutableListOf<String>()
    private val createdTreeIds = mutableListOf<String>()

    @Before
    fun setUp() {
        store = StrategyPresetStore(service, activeManagerHolder, catalogLoader)
    }

    @After
    fun tearDown() {
        createdPresetIds.forEach { service.deletePreset(it) }
        createdTreeIds.forEach { treeRepository.deleteById(it) }
    }

    private fun insertGlobalTree(id: String, tags: List<String>): String {
        treeRepository.save(
            TreeConfigEntity(
                id = id,
                bindingType = "PURPOSE_TAG",
                bindingIds = tags.joinToString(","),
                name = id,
                configData = """{"root":{"Leaf":{"payload":{"Rule":{"nodeId":"r1"}}}}}""",
                enabled = true,
                managerId = null
            )
        )
        createdTreeIds += id
        return id
    }

    @Test
    fun `savePreset 整体保存树白名单与时序三态覆盖`() {
        val treeClean = insertGlobalTree("TG_ED_TREE_CLEAN", listOf("CLEAN"))
        val treeDraw = insertGlobalTree("TG_ED_TREE_DRAW", listOf("DRAW_CARD"))

        val treeSelections = mapOf(
            "CLEAN" to listOf(treeClean),
            "DRAW_CARD" to listOf(treeDraw)
        )
        val timings = mapOf(
            "CLEAN" to TimingOverride(
                defaultStage = "FIRST",
                defaultOrderWeight = 3.5,
                defaultReplanAfterUse = true,
                surplusIdleThreshold = ThresholdPatch(2)
            ),
            "DRAW_CARD" to TimingOverride(
                defaultStage = "MID",
                defaultOrderWeight = 1.0,
                defaultReplanAfterUse = false,
                surplusIdleThreshold = ThresholdPatch(null) // 清除为无门槛
            )
        )

        // 1. 新建完整预设
        val err = store.savePreset(null, "TG_FULL_PRESET", "完整预设测试", treeSelections, timings)
        assertNull("保存应成功", err)
        val presetId = store.state.selectedPresetId
        assertNotNull(presetId)
        createdPresetIds += presetId!!

        // 2. 读回并校验两维度数据
        val detail = service.findDetail(presetId)
        assertNotNull(detail)
        assertEquals("TG_FULL_PRESET", detail!!.preset.name)
        assertEquals(setOf(treeClean), detail.treeSelections["CLEAN"])
        assertEquals(setOf(treeDraw), detail.treeSelections["DRAW_CARD"])

        val cleanTiming = detail.timings["CLEAN"]
        assertNotNull(cleanTiming)
        assertEquals("FIRST", cleanTiming!!.defaultStage)
        assertEquals(3.5, cleanTiming.defaultOrderWeight!!, 0.0)
        assertEquals(true, cleanTiming.defaultReplanAfterUse)
        assertEquals(2, cleanTiming.surplusIdleThreshold?.value)

        val drawTiming = detail.timings["DRAW_CARD"]
        assertNotNull(drawTiming)
        assertEquals("MID", drawTiming!!.defaultStage)
        assertEquals(false, drawTiming.defaultReplanAfterUse)
        assertNotNull("门槛已声明", drawTiming.surplusIdleThreshold)
        assertNull("清除为无门槛时 value 应为 null", drawTiming.surplusIdleThreshold?.value)
    }

    @Test
    fun `空预设整体保存合法且树项数为0`() {
        val err = store.savePreset(null, "TG_EMPTY_PRESET", "空预设测试", emptyMap(), emptyMap())
        assertNull("保存空预设应成功", err)
        val presetId = store.state.selectedPresetId
        assertNotNull(presetId)
        createdPresetIds += presetId!!

        val detail = service.findDetail(presetId)
        assertNotNull(detail)
        assertTrue("空预设树选择应为空", detail!!.treeSelections.isEmpty())
        assertTrue("空预设时序覆盖应为空", detail.timings.isEmpty())

        val summary = service.listPresets().find { it.preset.id == presetId }
        assertNotNull(summary)
        assertEquals(0, summary!!.treeItemCount)
        assertEquals(0, summary.timingCount)
    }

    @Test
    fun `loadInitialData 加载的候选树排除了卡组专属树`() {
        val globalTree = insertGlobalTree("TG_CAN_GLOBAL", listOf("CLEAN"))
        val deckTree = "TG_CAN_DECK_EXCL"
        treeRepository.save(
            TreeConfigEntity(
                id = deckTree,
                bindingType = "PURPOSE_TAG",
                bindingIds = "CLEAN",
                name = deckTree,
                configData = """{"root":{"Leaf":{"payload":{"Rule":{"nodeId":"r1"}}}}}""",
                enabled = true,
                managerId = "DECK_EXCL"
            )
        )
        createdTreeIds += deckTree

        store.loadInitialData()
        val candidateIds = store.state.candidateTrees.map { it.id }

        assertTrue("候选树应包含全局树", globalTree in candidateIds)
        assertFalse("候选树坚决不应包含卡组专属树 (D-TG-015)", deckTree in candidateIds)
    }
}
