package lin.ui.strategy_preset

import lin.mcp.McpTestEnv
import lin.repository.card_group.*
import lin.repository.tree_config.TreeConfigEntity
import lin.repository.tree_config.TreeConfigRepository
import lin.ui.card_group.ActiveManagerHolder
import lin.ui.card_group.WorkbenchStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.koin.core.context.GlobalContext

/**
 * T-TG-019：UI 端到端业务全流程走查与 UI ↔ MCP 交叉验证测试。
 *
 * 走查清单（严格遵循 T-TG-019 与 Q1~Q9）：
 * 1. UI 流程新建预设（含名称、描述）；
 * 2. 编辑树白名单与时序覆盖（含三态门槛设值与清除为 null）；
 * 3. 卡组工作台加载卡组并关联此预设；
 * 4. 引用中被删除拒绝阻断（Q6：列出引用卡组并阻断删除）；
 * 5. 卡组增量项（微调层 Delta）微调编辑与保存；
 * 6. 解除预设引用；
 * 7. 预设工作台执行物理删除（D-TG-016：UI 物理删除不落快照）；
 * 8. UI ↔ MCP 交叉读回验证：同一 service 底座读出完全一致。
 */
class PresetUiEndToEndWorkflowTest : McpTestEnv() {

    private val presetService: StrategyPresetService by lazy {
        GlobalContext.get().get<StrategyPresetService>()
    }
    private val cardGroupService: CardGroupService by lazy {
        GlobalContext.get().get<CardGroupService>()
    }
    private val treeRepository: TreeConfigRepository by lazy {
        GlobalContext.get().get<TreeConfigRepository>()
    }
    private val catalogLoader: lin.ui.service.PresetCatalogLoader by lazy {
        GlobalContext.get().get<lin.ui.service.PresetCatalogLoader>()
    }
    private val activeManagerHolder: ActiveManagerHolder by lazy {
        GlobalContext.get().get<ActiveManagerHolder>()
    }
    private val cascadeDeleteService: lin.repository.card_group.CardGroupCascadeDeleteService by lazy {
        GlobalContext.get().get()
    }

    private lateinit var presetStore: StrategyPresetStore
    private lateinit var cardGroupStore: WorkbenchStore

    private val createdPresetIds = mutableListOf<String>()
    private val createdManagerIds = mutableListOf<String>()
    private val createdTreeIds = mutableListOf<String>()

    @Before
    fun setUp() {
        presetStore = StrategyPresetStore(presetService, activeManagerHolder, catalogLoader)
        cardGroupStore = WorkbenchStore(cardGroupService, presetService, catalogLoader, cascadeDeleteService)
    }

    @After
    fun tearDown() {
        // ⚠️ 必须走**级联删**：本用例写过卡组增量项，`cardGroupService.deleteManager` 会留下孤儿维度项
        createdManagerIds.forEach { cascadeDeleteService.cascadeDelete(it) }
        createdPresetIds.forEach { presetService.deletePreset(it) }
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
        createdTreeIds.add(id)
        return id
    }

    @Test
    fun testFullUiWorkflowAndCrossVerification() {
        // 0. 准备全局树
        val drawTree = insertGlobalTree("TG_E2E_DRAW_TREE", listOf("DRAW"))
        val surplusTree = insertGlobalTree("TG_E2E_SURPLUS_TREE", listOf("SURPLUS"))

        // 1. 预设工作台：新建预设
        presetStore.loadInitialData()
        presetStore.enterCreatingMode()
        assertTrue("进入新建模式", presetStore.state.isCreating)

        val treeSelections = mapOf(
            "DRAW" to listOf(drawTree),
            "SURPLUS" to listOf(surplusTree)
        )
        val timings = mapOf(
            "DRAW" to TimingOverride(
                defaultStage = "FIRST",
                defaultOrderWeight = 2.0,
                defaultReplanAfterUse = true
            ),
            "SURPLUS" to TimingOverride(
                defaultStage = "MID",
                defaultOrderWeight = 1.0,
                defaultReplanAfterUse = false
            )
        )
        // T-TG-029：惜售门槛是**独立维度**（三态：声明值 / 声明为无门槛 / 不声明）
        val surplus = mapOf(
            "DRAW" to SurplusOverride(surplusIdleThreshold = ThresholdPatch(3)),
            "SURPLUS" to SurplusOverride(surplusIdleThreshold = ThresholdPatch(null))
        )

        val saveErr = presetStore.savePreset(
            presetId = null,
            name = "E2E全流程预设",
            description = "端到端联调测试预设",
            treeSelections = treeSelections,
            timings = timings,
            surplus = surplus
        )
        assertNull("保存预设成功", saveErr)

        val presetId = presetStore.state.selectedPresetId
        assertNotNull("保存后应选中新生成的 presetId", presetId)
        createdPresetIds.add(presetId!!)

        // 2. 交叉读回校验：service.findDetail 验证数据一致
        val detail = presetService.findDetail(presetId)
        assertNotNull("应能通过 service 查出预设详情", detail)
        assertEquals("E2E全流程预设", detail!!.preset.name)
        assertEquals(listOf(drawTree), detail.treeSelections["DRAW"]?.toList())
        assertEquals(3, detail.surplus["DRAW"]?.surplusIdleThreshold?.value)
        assertNull("SURPLUS 门槛应被声明为无门槛（value = null）", detail.surplus["SURPLUS"]?.surplusIdleThreshold?.value)
        assertNotNull("且属于「已声明」而非「未声明」", detail.surplus["SURPLUS"]?.surplusIdleThreshold)

        // 3. 卡组工作台：新建卡组并关联此预设
        val managerId = cardGroupService.saveManager(
            ManagerSaveCommand(
                name = "E2E测试卡组",
                sourceFile = "e2e_test.cardgroup",
                enabled = true,
                bindings = emptyList()
            )
        )
        createdManagerIds.add(managerId)

        cardGroupStore.loadInitialData()
        val managerItem = cardGroupStore.state.managers.find { it.entity?.id == managerId }
        assertNotNull("卡组列表中应包含新建卡组", managerItem)
        cardGroupStore.selectManager(managerItem)

        val linkErr = cardGroupStore.setCardGroupPreset(presetId)
        assertNull("卡组关联预设成功", linkErr)
        assertEquals(presetId, cardGroupStore.state.managerPresetId)
        assertEquals("E2E全流程预设", cardGroupStore.state.currentPresetDetail?.preset?.name)

        // 4. 引用防呆阻断：预设被引用时拒绝删除（Q6）
        val refs = presetService.findReferences(presetId)
        assertEquals("引用计数应为 1", 1, refs.size)
        assertEquals("引用卡组名称匹配", "E2E测试卡组", refs.first().managerName)

        // 尝试通过 deleteOps 触发删除拒绝
        try {
            presetService.deleteOps().collect(presetId)
            fail("被引用时导出删除操作值应抛出 SnapshotRefused")
        } catch (e: Exception) {
            assertTrue("异常信息应包含引用卡组提示", e.message?.contains("以下卡组引用此预设") == true)
        }

        // 5. 卡组微调层（Delta）：设置微调项并持久化
        val deckDeltaExclusions = mapOf("DRAW" to listOf(drawTree)) // 本卡组额外排除 drawTree
        val deckDeltaTimings = mapOf(
            "DRAW" to TimingOverride(defaultStage = "LATE", defaultOrderWeight = 99.0)
        )
        val deltaErr = cardGroupStore.saveDeckDelta(deckDeltaExclusions, deckDeltaTimings)
        assertNull("保存卡组增量项成功", deltaErr)

        val deltaFromDb = presetService.findDeckDelta(managerId)
        assertEquals(listOf(drawTree), deltaFromDb.treeExclusions["DRAW"]?.toList())
        assertEquals("LATE", deltaFromDb.timings["DRAW"]?.defaultStage)
        assertEquals(99.0, deltaFromDb.timings["DRAW"]?.defaultOrderWeight)

        // 6. 卡组侧解除引用：设为 null
        val unlinkErr = cardGroupStore.setCardGroupPreset(null)
        assertNull("解除预设成功", unlinkErr)
        assertNull("卡组预设 ID 已清空", cardGroupStore.state.managerPresetId)

        val refsAfterUnlink = presetService.findReferences(presetId)
        assertTrue("解除后引用计数应为 0", refsAfterUnlink.isEmpty())

        // 7. 预设工作台：执行物理删除（D-TG-016）
        val deletedPreset = presetStore.deletePreset(presetId)
        assertNotNull("预设删除成功", deletedPreset)

        val deletedDetail = presetService.findDetail(presetId)
        assertNull("预设已从数据库中物理删除", deletedDetail)
    }
}
