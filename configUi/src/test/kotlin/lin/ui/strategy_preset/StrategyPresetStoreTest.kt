package lin.ui.strategy_preset

import lin.mcp.McpTestEnv
import lin.repository.card_group.PresetSaveInput
import lin.repository.card_group.StrategyPresetRepository
import lin.repository.card_group.StrategyPresetService
import lin.ui.card_group.ActiveManagerHolder
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.koin.core.context.GlobalContext

/**
 * T-TG-016：策略预设工作台 Store 状态流转单元测试。
 */
class StrategyPresetStoreTest : McpTestEnv() {

    private val service: StrategyPresetService by lazy {
        GlobalContext.get().get<StrategyPresetService>()
    }
    private val presetRepository: StrategyPresetRepository by lazy {
        GlobalContext.get().get<StrategyPresetRepository>()
    }
    private val activeManagerHolder: ActiveManagerHolder by lazy {
        GlobalContext.get().get<ActiveManagerHolder>()
    }
    private val catalogLoader: lin.ui.service.PresetCatalogLoader by lazy {
        GlobalContext.get().get<lin.ui.service.PresetCatalogLoader>()
    }

    private lateinit var store: StrategyPresetStore
    private val createdPresetIds = mutableListOf<String>()

    @Before
    fun setUp() {
        store = StrategyPresetStore(service, activeManagerHolder, catalogLoader)
    }

    @After
    fun tearDown() {
        createdPresetIds.forEach { service.deletePreset(it) }
    }

    @Test
    fun `loadInitialData 加载预设列表并默认选中首项`() {
        val p1 = service.savePreset(PresetSaveInput(null, "TG_STORE_P1", "描述1"))!!.presetId
        val p2 = service.savePreset(PresetSaveInput(null, "TG_STORE_P2", "描述2"))!!.presetId
        createdPresetIds += listOf(p1, p2)

        store.loadInitialData()
        val state = store.state

        assertTrue("全部预设应包含新建的预设", state.allPresets.any { it.preset.id == p1 })
        assertTrue("全部预设应包含新建的预设", state.allPresets.any { it.preset.id == p2 })
        assertNotNull("默认应选中某项预设", state.selectedPresetId)
        assertNotNull("选中的预设详情应已加载", state.selectedDetail)
    }

    @Test
    fun `updateSearchText 能根据名称与描述过滤预设`() {
        val p1 = service.savePreset(PresetSaveInput(null, "SEARCH_ALPHA", "通用快攻"))!!.presetId
        val p2 = service.savePreset(PresetSaveInput(null, "SEARCH_BETA", "慢速控制"))!!.presetId
        createdPresetIds += listOf(p1, p2)

        store.loadInitialData()

        // 搜索 "ALPHA"
        store.updateSearchText("ALPHA")
        val state1 = store.state
        assertEquals("ALPHA", state1.searchText)
        assertTrue("应包含 ALPHA", state1.filteredPresets.any { it.preset.id == p1 })
        assertFalse("不应包含 BETA", state1.filteredPresets.any { it.preset.id == p2 })
        assertEquals(p1, state1.selectedPresetId)

        // 搜索描述 "慢速"
        store.updateSearchText("慢速")
        val state2 = store.state
        assertTrue("应包含 BETA", state2.filteredPresets.any { it.preset.id == p2 })
        assertFalse("不应包含 ALPHA", state2.filteredPresets.any { it.preset.id == p1 })
        assertEquals(p2, state2.selectedPresetId)
    }

    @Test
    fun `savePresetMetadata 新建与重命名预设`() {
        // 1. 空名称校验拦截
        val emptyErr = store.savePresetMetadata(null, "   ", "描述")
        assertNotNull("空名称应报错", emptyErr)

        // 2. 新建预设
        val createErr = store.savePresetMetadata(null, "TG_NEW_PRESET", "新建的预设描述")
        assertNull("新建应成功", createErr)
        val createdId = store.state.selectedPresetId
        assertNotNull("新建后应自动选中", createdId)
        createdPresetIds += createdId!!

        val detail = store.state.selectedDetail
        assertNotNull(detail)
        assertEquals("TG_NEW_PRESET", detail!!.preset.name)
        assertEquals("新建的预设描述", detail.preset.description)

        // 3. 重命名预设
        val updateErr = store.savePresetMetadata(createdId, "TG_RENAMED_PRESET", "更新后的描述")
        assertNull("更新应成功", updateErr)
        val updatedDetail = store.state.selectedDetail
        assertNotNull(updatedDetail)
        assertEquals("TG_RENAMED_PRESET", updatedDetail!!.preset.name)
        assertEquals("更新后的描述", updatedDetail.preset.description)
    }

    @Test
    fun `deletePreset 删除预设并刷新状态`() {
        val p1 = service.savePreset(PresetSaveInput(null, "TG_TO_DEL", "即将被删"))!!.presetId
        store.loadInitialData()
        store.selectPreset(p1)
        assertEquals(p1, store.state.selectedPresetId)

        val deleteError = store.deletePreset(p1)
        assertNull("删除应成功", deleteError)
        assertFalse("列表中不应再包含该预设", store.state.allPresets.any { it.preset.id == p1 })
        assertNotEquals("当前选中的预设不能是被删的预设", p1, store.state.selectedPresetId)
    }

    @Test
    fun `enterCreatingMode 进入新建模式`() {
        val p1 = service.savePreset(PresetSaveInput(null, "TG_BEFORE_CREATE", null))!!.presetId
        createdPresetIds += p1
        store.loadInitialData()
        store.selectPreset(p1)
        assertFalse(store.state.isCreating)

        store.enterCreatingMode()
        assertTrue("应处于新建模式", store.state.isCreating)
        assertNull("新建模式下应无选中 ID", store.state.selectedPresetId)
        assertNull("新建模式下应无详情", store.state.selectedDetail)
    }
}
