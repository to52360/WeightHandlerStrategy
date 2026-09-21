package lin.repository.tree_config

import lin.mcp.McpTestEnv
import lin.repository.card_group.CardGroupRepository
import lin.repository.card_group.CardManagerEntity
import lin.repository.card_group.PresetSaveInput
import lin.repository.card_group.StrategyPresetRepository
import lin.repository.card_group.StrategyPresetService
import lin.ui.service.TreeConfigService
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext

/**
 * T-TG-024：阶段四能力前置测试。
 *
 * 验证：
 * 1. [TreeConfigRepository.findGlobalByBindingType] 仅查全局共享（manager_id IS NULL）且指定 bindingType 的树；
 * 2. [TreeConfigService.purposeTagUniverse] 单点口径仅统计全局已启用的用途树（卡组专属树排除）；
 * 3. [CardGroupRepository.findManagersByPresetId] 与 [StrategyPresetService.listPresets] 的引用聚合正确性。
 */
class TreeConfigGlobalQueryTest : McpTestEnv() {

    private val treeRepository: TreeConfigRepository by lazy {
        GlobalContext.get().get<TreeConfigRepository>()
    }
    private val treeService: TreeConfigService by lazy {
        GlobalContext.get().get<TreeConfigService>()
    }
    private val groupRepository: CardGroupRepository by lazy {
        GlobalContext.get().get<CardGroupRepository>()
    }
    private val presetService: StrategyPresetService by lazy {
        GlobalContext.get().get<StrategyPresetService>()
    }
    private val presetRepository: StrategyPresetRepository by lazy {
        GlobalContext.get().get<StrategyPresetRepository>()
    }

    private val createdTreeIds = mutableListOf<String>()
    private val createdDeckIds = mutableListOf<String>()
    private var testPresetId: String? = null

    @After
    fun cleanUp() {
        testPresetId?.let { presetService.deletePreset(it) }
        createdTreeIds.forEach { treeRepository.deleteById(it) }
        createdDeckIds.forEach { groupRepository.deleteManager(it) }
    }

    private fun insertTree(
        id: String,
        bindingType: String,
        tags: String,
        enabled: Boolean = true,
        managerId: String? = null
    ): String {
        treeRepository.save(
            TreeConfigEntity(
                id = id,
                bindingType = bindingType,
                bindingIds = tags,
                name = id,
                configData = """{"root":{"Leaf":{"payload":{"Rule":{"nodeId":"r1"}}}}}""",
                enabled = enabled,
                managerId = managerId
            )
        )
        createdTreeIds += id
        return id
    }

    @Test
    fun `findGlobalByBindingType 仅返回 manager_id 为 null 且匹配 bindingType 的树`() {
        val globalPurposeTree = insertTree("TG_Q_GLOBAL_P", "PURPOSE_TAG", "CLEAN", enabled = true, managerId = null)
        val deckPurposeTree = insertTree("TG_Q_DECK_P", "PURPOSE_TAG", "DRAW", enabled = true, managerId = "DECK_01")
        val globalGroupTree = insertTree("TG_Q_GLOBAL_G", "GROUP", "G1", enabled = true, managerId = null)
        val disabledGlobalTree =
            insertTree("TG_Q_DIS_GLOBAL_P", "PURPOSE_TAG", "HEAL", enabled = false, managerId = null)

        // 全部全局用途树（含禁用）
        val allGlobalPurposes = treeRepository.findGlobalByBindingType("PURPOSE_TAG", enabledOnly = false)
        val allIds = allGlobalPurposes.map { it.id }
        assertTrue("应包含全局启用用途树", globalPurposeTree in allIds)
        assertTrue("应包含全局禁用用途树", disabledGlobalTree in allIds)
        assertFalse("不应包含卡组专属用途树", deckPurposeTree in allIds)
        assertFalse("不应包含 GROUP 树", globalGroupTree in allIds)

        // 仅启用全局用途树
        val enabledGlobalPurposes = treeRepository.findGlobalByBindingType("PURPOSE_TAG", enabledOnly = true)
        val enabledIds = enabledGlobalPurposes.map { it.id }
        assertTrue("应包含全局启用用途树", globalPurposeTree in enabledIds)
        assertFalse("enabledOnly=true 时不应包含禁用用途树", disabledGlobalTree in enabledIds)
        assertFalse("不应包含卡组专属用途树", deckPurposeTree in enabledIds)
    }

    @Test
    fun `purposeTagUniverse 仅统计全局启用的用途树且排除专属树与禁用树`() {
        insertTree("TG_U_GLOBAL_1", "PURPOSE_TAG", "TAG_A,TAG_B", enabled = true, managerId = null)
        insertTree("TG_U_GLOBAL_DISABLED", "PURPOSE_TAG", "TAG_DISABLED", enabled = false, managerId = null)
        insertTree("TG_U_DECK_PRIVATE", "PURPOSE_TAG", "TAG_PRIVATE", enabled = true, managerId = "DECK_OWNER")

        val universe = treeService.purposeTagUniverse()
        assertTrue("应包含全局启用的 TAG_A", "TAG_A" in universe)
        assertTrue("应包含全局启用的 TAG_B", "TAG_B" in universe)
        assertFalse("已禁用的全局用途树不应计入 universe", "TAG_DISABLED" in universe)
        assertFalse("卡组专属用途树绝不应计入 universe (D-TG-015)", "TAG_PRIVATE" in universe)
    }

    @Test
    fun `findManagersByPresetId 与 listPresets 引用聚合正确`() {
        val preset = presetService.savePreset(PresetSaveInput(null, "TG_REF_PRESET", null))
        assertNotNull(preset)
        testPresetId = preset!!.presetId

        val deck1 = "TG_DECK_REF_1"
        val deck2 = "TG_DECK_REF_2"
        val deckNoRef = "TG_DECK_NO_REF"
        groupRepository.saveManager(CardManagerEntity(id = deck1, name = "卡组1", sourceFile = "1.cg", enabled = true))
        groupRepository.saveManager(CardManagerEntity(id = deck2, name = "卡组2", sourceFile = "2.cg", enabled = true))
        groupRepository.saveManager(
            CardManagerEntity(
                id = deckNoRef,
                name = "卡组3",
                sourceFile = "3.cg",
                enabled = true
            )
        )
        createdDeckIds += listOf(deck1, deck2, deckNoRef)

        groupRepository.updateManagerPreset(deck1, testPresetId)
        groupRepository.updateManagerPreset(deck2, testPresetId)

        // 验证按 presetId 查询
        val refs = groupRepository.findManagersByPresetId(testPresetId!!)
        assertEquals(2, refs.size)
        val refDeckIds = refs.map { it.id }.toSet()
        assertEquals(setOf(deck1, deck2), refDeckIds)

        // 验证 StrategyPresetService.findReferences
        val serviceRefs = presetService.findReferences(testPresetId!!)
        assertEquals(2, serviceRefs.size)
        assertEquals(setOf(deck1, deck2), serviceRefs.map { it.managerId }.toSet())

        // 验证 listPresets 批量聚合
        val allPresets = presetService.listPresets()
        val foundSummary = allPresets.firstOrNull { it.preset.id == testPresetId }
        assertNotNull(foundSummary)
        assertEquals(2, foundSummary!!.referencedBy.size)
        assertEquals(setOf(deck1, deck2), foundSummary.referencedBy.map { it.managerId }.toSet())
    }
}
