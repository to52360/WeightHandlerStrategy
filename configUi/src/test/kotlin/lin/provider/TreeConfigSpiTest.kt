package lin.provider

import lin.mcp.McpTestEnv
import lin.repository.card_group.CardGroupService
import lin.repository.card_group.CurrentDeckContext
import lin.repository.card_group.DimensionItemResolver
import lin.repository.card_group.StrategyPresetRepository
import lin.repository.tree_config.TreeConfigRepository
import lin.rule.tree.EvaluatorTreeBindingType
import lin.ui.card_purpose.PurposeTagTreeBindingPolicy
import lin.ui.service.TreeConfigService
import lin.ui.service.createTreeConfigMapper
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext

/**
 * SPI 侧评估树加载的回归网 —— K-TG-007（反序列化全失败）/ K-TG-008（GROUP 绑定 id 空间）修复验收。
 *
 * 用**库中真实树**（只读，不改数据）：诊断时实测 `DB enabled 树 = 3` 而 `SPI 输出 = 0`。
 */
class TreeConfigSpiTest : McpTestEnv() {

    private val treeRepository: TreeConfigRepository by lazy { GlobalContext.get().get() }
    private val cardGroupService: CardGroupService by lazy { GlobalContext.get().get() }
    private val groupRepository: lin.repository.card_group.CardGroupRepository by lazy { GlobalContext.get().get() }

    private val testManagerId = "TG_TREE_SPI_DECK"
    private val testBindingId = "TG_TREE_SPI_BINDING"
    private val testTreeId = "TG_TREE_SPI_GROUP_TREE"
    private val testTagTreeId = "TG_TREE_SPI_TAG_TREE"
    private val testGlobalTreeId = "TG_Q004_GLOBAL_TREE"
    private val testOwnTreeId = "TG_Q004_OWN_TREE"
    private val testDanglingTreeId = "TG_Q004_DANGLING_TREE"
    private val testPresetId = "TG_Q004_PRESET"

    /** 用内置用途标签（保证已在 `purpose_tag_def` 中 ⇒ 在 `tagPolicy.enabledTags` 内）。 */
    private val testPresetTag = "CLEAN"

    @org.junit.After
    fun cleanUp() {
        treeRepository.deleteById(testTreeId)
        treeRepository.deleteById(testTagTreeId)
        treeRepository.deleteById(testGlobalTreeId)
        treeRepository.deleteById(testOwnTreeId)
        treeRepository.deleteById(testDanglingTreeId)
        GlobalContext.get().get<StrategyPresetRepository>().deletePreset(testPresetId)
        groupRepository.deleteManager(testManagerId)
    }

    private fun provider(): SqliteTreeConfigProvider = SqliteTreeConfigProvider(
        treeConfigService = TreeConfigService(
            repository = GlobalContext.get().get(),
            leafConfigRepository = GlobalContext.get().get(),
            mapper = createTreeConfigMapper(),
            tx = GlobalContext.get().get()
        ),
        cardGroupService = cardGroupService,
        tagPolicy = PurposeTagTreeBindingPolicy(GlobalContext.get().get()),
        presetRepository = GlobalContext.get().get<StrategyPresetRepository>(),
        currentDeck = CurrentDeckContext(GlobalContext.get().get()),
        resolver = DimensionItemResolver()
    )

    /**
     * K-TG-007：`config_data` 只存 `root`，组装必须走 `TreeConfigService`
     * ⇒ 分组/单卡绑定的树**不得整批丢失**（用途树可因预设白名单被裁，故单独排除）。
     */
    @Test
    fun `库中已启用的分组与单卡绑定树都应加载出来`() {
        val dbCount = treeRepository.findAll()
            .count { it.enabled && it.bindingType != EvaluatorTreeBindingType.PURPOSE_TAG.name }
        val loadedCount = provider().findAll()
            .count { it.bindingType != EvaluatorTreeBindingType.PURPOSE_TAG }

        assertTrue("库中应存在已启用的分组/单卡树", dbCount > 0)
        assertEquals("K-TG-007：SPI 不得整批丢失评估树", dbCount, loadedCount)
    }

    /** K-TG-007：叶子配置也要组装进来（否则树虽在、叶子编译必失败）。 */
    @Test
    fun `加载出的树带上了叶子配置`() {
        val withLeaves = GlobalContext.get().get<lin.repository.tree_config.EvaluatorLeafConfigRepository>()
            .findAllRaw()
            .map { it.first }
            .toSet()
        assertTrue("库中应存在叶子配置数据", withLeaves.isNotEmpty())

        val loaded = provider().findAll()
        assertTrue(
            "至少有一棵树带上了叶子配置",
            loaded.any { it.leafConfigs.isNotEmpty() }
        )
    }

    /**
     * K-TG-008：`GROUP` 树的 `bindingIds` 是**绑定条目 id**，过滤集必须同空间
     * ⇒ 绑定必须原样保留（此前拿 manager id 比 ⇒ 恒 false ⇒ 整棵剔空）。
     */
    @Test
    fun `分组树保留启用卡组下的绑定条目`() {
        // 建一个启用卡组 + 一条绑定 + 一棵绑它的 GROUP 树（直接走 repository，避开 saveManager 的单活联动）
        groupRepository.saveManager(
            lin.repository.card_group.CardManagerEntity(
                id = testManagerId, name = testManagerId,
                sourceFile = "$testManagerId.cardgroup", enabled = true
            )
        )
        groupRepository.saveBinding(
            lin.repository.card_group.CardBindingEntity(
                id = testBindingId, managerId = testManagerId, name = "g", cardIds = "[]"
            )
        )
        treeRepository.save(
            lin.repository.tree_config.TreeConfigEntity(
                id = testTreeId,
                bindingType = EvaluatorTreeBindingType.GROUP.name,
                bindingIds = testBindingId,
                name = testTreeId,
                configData = ROOT_JSON,
                enabled = true
            )
        )

        // 前置：引擎侧确实能看到这条绑定
        val enabledBindingIds = cardGroupService.loadAll(onlyEnabled = true)
            .flatMap { manager -> manager.bindings.map { it.id } }
            .toSet()
        assertTrue("启用卡组下应能看到测试绑定", testBindingId in enabledBindingIds)

        val mine = provider().findAll().firstOrNull { testBindingId in it.bindingIds }
        assertNotNull("K-TG-008：GROUP 树不得因 id 空间不匹配被剔空", mine)
        assertEquals(listOf(testBindingId), mine!!.bindingIds)
    }

    /**
     * T-TG-020：卡组引用的预设**行已不存在**（悬空引用）⇒ 语义保持「引用即受控」，
     * 白名单取到空集 ⇒ 用途树全禁（等同空预设），**不**回落成"全部生效"。
     *
     * 对照组（未引用预设 ⇒ 该树正常输出）证明本用例的判定链是通的，不是恒 null 蒙对。
     */
    @Test
    fun `悬空预设引用使用途树全禁而未被静默放行`() {
        assertTrue(
            "前置：$testPresetTag 必须是启用标签，否则该用途树本就出不来、用例证不了任何事",
            testPresetTag in PurposeTagTreeBindingPolicy(GlobalContext.get().get()).enabledTags.map { it.value }
        )
        groupRepository.saveManager(
            lin.repository.card_group.CardManagerEntity(
                id = testManagerId, name = testManagerId,
                sourceFile = "$testManagerId.cardgroup", enabled = true
            )
        )
        treeRepository.save(
            lin.repository.tree_config.TreeConfigEntity(
                id = testTagTreeId,
                bindingType = EvaluatorTreeBindingType.PURPOSE_TAG.name,
                bindingIds = testPresetTag,
                name = testTagTreeId,
                configData = ROOT_JSON,
                enabled = true
            )
        )
        assertEquals(
            "前置：本用例的卡组应就是「当前卡组」（库中不应有其它 enabled 卡组）",
            testManagerId,
            CurrentDeckContext(groupRepository).current()?.id
        )

        // 对照组：未引用预设 ⇒ 用途树正常输出
        assertNotNull("未引用预设时用途树应输出", provider().findById(testTagTreeId))

        // 悬空引用：卡组指向一个不存在的预设
        groupRepository.updateManagerPreset(testManagerId, DANGLING_PRESET_ID)
        assertNull(
            "T-TG-020：悬空引用 ⇒ 全禁（不得回落成兜底全开）",
            provider().findById(testTagTreeId)
        )
    }

    /**
     * Q-TG-004（形态 D）：`PURPOSE_TAG` 树的 `manager_id` = **归属卡组**。
     *
     * - 归属**本卡组** ⇒ 可见，且**跳过预设白名单**（归属即拥有；预设管的是公共资源怎么用）
     * - 归属**他组 / 不存在的卡组** ⇒ 整棵不输出
     *
     * 对照组：同用途的**无归属（全局共享）树**被"该用途声明零棵树"的空集白名单裁掉 ⇒ 证明白名单确实在起作用。
     */
    @Test
    fun `归属本卡组的用途树跳过预设白名单而他组与悬空归属不输出`() {
        assertTrue(
            "前置：$testPresetTag 必须是启用标签",
            testPresetTag in PurposeTagTreeBindingPolicy(GlobalContext.get().get()).enabledTags.map { it.value }
        )
        val presetRepository = GlobalContext.get().get<StrategyPresetRepository>()
        groupRepository.saveManager(
            lin.repository.card_group.CardManagerEntity(
                id = testManagerId, name = testManagerId,
                sourceFile = "$testManagerId.cardgroup", enabled = true
            )
        )
        // 三棵树绑同一用途：全局共享 / 本卡组专属 / 悬空归属
        savePurposeTagTree(testGlobalTreeId, managerId = null)
        savePurposeTagTree(testOwnTreeId, managerId = testManagerId)
        savePurposeTagTree(testDanglingTreeId, managerId = "TG_Q004_NO_SUCH_DECK")

        // 预设对该用途声明"保留零棵树"（空集）⇒ 全局共享树该被裁掉
        presetRepository.savePreset(
            lin.repository.card_group.StrategyPresetEntity(id = testPresetId, name = "q004", description = null, createdAt = null)
        )
        presetRepository.replaceTreeSelections(
            lin.repository.card_group.DimensionScope.PRESET, testPresetId, mapOf(testPresetTag to emptyList())
        )
        groupRepository.updateManagerPreset(testManagerId, testPresetId)
        assertEquals(
            "前置：本用例的卡组应就是「当前卡组」",
            testManagerId,
            CurrentDeckContext(groupRepository).current()?.id
        )

        assertNull("全局共享树应被空集白名单裁掉（证明白名单在起作用）", provider().findById(testGlobalTreeId))
        assertNotNull("Q-TG-004：归属本卡组的专属树不受白名单约束", provider().findById(testOwnTreeId))
        assertNull("Q-TG-004：悬空归属的用途树应整棵不输出（不静默放行）", provider().findById(testDanglingTreeId))
    }

    private fun savePurposeTagTree(treeId: String, managerId: String?) {
        treeRepository.save(
            lin.repository.tree_config.TreeConfigEntity(
                id = treeId,
                bindingType = EvaluatorTreeBindingType.PURPOSE_TAG.name,
                bindingIds = testPresetTag,
                name = treeId,
                configData = ROOT_JSON,
                enabled = true,
                managerId = managerId
            )
        )
    }

    private companion object {
        /** 只用于探针：本用例不做反序列化断言，但 provider 会走组装（root 必须可解）。 */
        const val ROOT_JSON = """{"root":{"Leaf":{"payload":{"Rule":{"nodeId":"r1"}}}}}"""

        /** 刻意不存在的预设 id（用于构造悬空引用）。 */
        const val DANGLING_PRESET_ID = "TG_TREE_SPI_NO_SUCH_PRESET"
    }
}
