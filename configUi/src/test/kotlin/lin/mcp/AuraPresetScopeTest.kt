package lin.mcp

import lin.repository.aura_boost.AuraBoostConfigService
import lin.repository.aura_boost.AuraBoostEntity
import lin.repository.aura_boost.AuraBoostRepository
import lin.repository.card_group.AuraDelta
import lin.repository.card_group.CardGroupRepository
import lin.repository.card_group.CardManagerEntity
import lin.repository.card_group.Dimension
import lin.repository.card_group.DimensionPayloadCodec
import lin.repository.card_group.DimensionScope
import lin.repository.card_group.StrategyPresetRepository
import lin.repository.card_group.StrategyPresetService
import lin.provider.SqliteAuraBoostConfigProvider
import lin.serviceLoader.provider.AuraBoostConfigProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koin.core.context.GlobalContext

/**
 * D-DP-001 / D-DP-002（光环入预设）最小验证集。
 *
 * 覆盖：候选池口径（未引用预设 = 无全局光环）、白名单 / extra / exclude / scoreOverrides 三档、
 * 私有行不受白名单约束、删除悬空守卫、同 id 双写报错、引用私有行报错，
 * 以及 Q-DP-002 §8.1 的两条实施假设断言（哨兵不污染 / EXCLUDABLE_DIMENSIONS 不变）。
 */
class AuraPresetScopeTest : McpTestEnv() {

    private val presetRepository: StrategyPresetRepository = GlobalContext.get().get()
    private val groupRepository: CardGroupRepository = GlobalContext.get().get()
    private val auraService: AuraBoostConfigService = GlobalContext.get().get()
    private val auraRepository: AuraBoostRepository = GlobalContext.get().get()
    private val presetService: StrategyPresetService = GlobalContext.get().get()

    /**
     * 引擎侧 SPI 实现**就地构造**（而非 `GlobalContext.get()`）：
     * `AuraBoostConfigProvider` 的绑定在 `ConfigUiStrategyProvidersInfo`，测试环境只加载 MCP 编排 module
     * ⇒ 直接组装同一实现，测的是「引擎视角的供给过滤」而非绑定本身。
     */
    private val auraProvider: AuraBoostConfigProvider = SqliteAuraBoostConfigProvider(
        service = auraService,
        cardGroupService = GlobalContext.get().get(),
        presetRepository = presetRepository,
        currentDeck = GlobalContext.get().get(),
        resolver = GlobalContext.get().get()
    )

    private val deckId = "DP_TEST_AURA_DECK"
    private val presetIds = mutableListOf<String>()
    private val auraIds = mutableListOf<String>()

    @After
    fun cleanupAuraScope() {
        // 顺序：先删预设（解除白名单引用）→ 卡组增量项 → 卡组 → 光环行（否则被守卫拒绝）
        presetIds.forEach { runCatching { presetService.deletePreset(it) } }
        runCatching { presetRepository.deleteItems(DimensionScope.CARD_GROUP, deckId) }
        runCatching { groupRepository.deleteManager(deckId) }
        auraIds.forEach { runCatching { auraService.delete(it) } }
    }

    /** 未引用预设 ⇒ 无全局光环；白名单纳入后才生效（替代原「全局行隐式生效」）。 */
    @Test
    fun `全局光环从隐式生效改为候选池`() {
        val aura = createAura("DP_TEST_GLOBAL_A", 2.0, managerId = null)
        createEnabledDeck()

        assertFalse("未引用预设时全局行不应生效", effectiveAuraIds().contains(aura))

        val pid = savePreset("""{"name":"DP_TEST_AURA_P1","auraBoostIds":["$aura"]}""")
        call("save_card_group_preset", """{"managerId":"$deckId","presetId":"$pid"}""")
        assertTrue("白名单纳入后应生效", effectiveAuraIds().contains(aura))
    }

    /** 卡组增量三档：extra 拉回 / exclude 再减 / scoreOverrides 覆盖分值。 */
    @Test
    fun `卡组增量三档_增_减_覆盖分值`() {
        val a1 = createAura("DP_TEST_G1", 1.0, managerId = null)
        val a2 = createAura("DP_TEST_G2", 2.0, managerId = null)
        createEnabledDeck()
        val pid = savePreset("""{"name":"DP_TEST_AURA_P2","auraBoostIds":["$a1","$a2"]}""")
        call("save_card_group_preset", """{"managerId":"$deckId","presetId":"$pid"}""")

        val excluded = call(
            "save_card_group_preset_delta",
            """{"managerId":"$deckId","excludedAuraBoostIds":["$a2"]}"""
        )
        assertFalse("三档保存应成功: ${excluded.contentJson}", excluded.isError)
        var configs = auraProvider.findAll()
        assertTrue("a1 应仍在", configs.any { it.id == a1 })
        assertFalse("exclude 应减掉 a2", configs.any { it.id == a2 })

        val overridden = call(
            "save_card_group_preset_delta",
            """{"managerId":"$deckId","auraScoreOverrides":{"$a1":9.5}}"""
        )
        assertFalse("分值覆盖应成功: ${overridden.contentJson}", overridden.isError)
        configs = auraProvider.findAll()
        assertEquals(9.5, configs.first { it.id == a1 }.score, 0.0001)

        // 只改一档时另两档必须保留（按档合并）：a2 仍被 exclude
        assertFalse("覆盖分值不应把 exclude 档冲掉", configs.any { it.id == a2 })
    }

    /** 私有行归属即拥有：不受白名单约束。 */
    @Test
    fun `卡组私有光环不受白名单约束`() {
        createEnabledDeck()
        val priv = createAura("DP_TEST_PRIV", 1.5, managerId = deckId)
        assertTrue("私有行应始终生效", effectiveAuraIds().contains(priv))
    }

    /** 守卫：被引用拒删 / 同 id 双写报错 / 引用私有行报错。 */
    @Test
    fun `守卫_被引用拒删_双写报错_引用私有行报错`() {
        val g = createAura("DP_TEST_G3", 1.0, managerId = null)
        createEnabledDeck()
        val pid = savePreset("""{"name":"DP_TEST_AURA_P3","auraBoostIds":["$g"]}""")
        call("save_card_group_preset", """{"managerId":"$deckId","presetId":"$pid"}""")

        val del = call("delete", """{"resource":"aura_boost","id":"$g"}""")
        assertTrue("被白名单引用应拒删: ${del.contentJson}", del.isError)
        assertTrue("拒删应回显引用方", del.contentJson.contains("仍被") || del.contentJson.contains("引用"))

        val dup = call(
            "save_card_group_preset_delta",
            """{"managerId":"$deckId","extraAuraBoostIds":["$g"],"excludedAuraBoostIds":["$g"]}"""
        )
        assertTrue("同 id 双写应报错: ${dup.contentJson}", dup.isError)

        val priv = createAura("DP_TEST_PRIV2", 1.0, managerId = deckId)
        val bad = call("save_strategy_preset", """{"name":"DP_TEST_AURA_P4","auraBoostIds":["$priv"]}""")
        assertTrue("引用私有行应报错: ${bad.contentJson}", bad.isError)
    }

    /** Q-DP-002 §8.1 的两条实施假设：哨兵不污染 + 「整用途退出」集合不变。 */
    @Test
    fun `实施假设断言_哨兵不污染且排除维度集合不变`() {
        assertEquals(
            "EXCLUDABLE_DIMENSIONS 必须仍是三个用途维度（AURA_BOOST 不是用途维度）",
            setOf(Dimension.PURPOSE_TREE, Dimension.PURPOSE_TIMING, Dimension.PURPOSE_SURPLUS),
            Dimension.EXCLUDABLE_DIMENSIONS
        )
        assertEquals(
            "历史 {} payload 必须仍解码为全集（整用途退出）",
            Dimension.EXCLUDABLE_DIMENSIONS,
            DimensionPayloadCodec.decodeExclusion("{}")
        )
        assertEquals(
            "缺省 payload 必须仍解码为全集",
            Dimension.EXCLUDABLE_DIMENSIONS,
            DimensionPayloadCodec.decodeExclusion(null)
        )

        val pid = savePreset("""{"name":"DP_TEST_AURA_P5","timings":[{"tagId":"CLEAN","defaultStage":"LATE"}]}""")
        val before = listOf(
            presetRepository.findTimings(DimensionScope.PRESET, pid),
            presetRepository.findSurplus(DimensionScope.PRESET, pid),
            presetRepository.findTreeSelections(DimensionScope.PRESET, pid),
            presetRepository.findExclusions(DimensionScope.PRESET, pid)
        )
        presetRepository.replaceAuraSelection(DimensionScope.PRESET, pid, listOf("sentinel_probe"))
        val after = listOf(
            presetRepository.findTimings(DimensionScope.PRESET, pid),
            presetRepository.findSurplus(DimensionScope.PRESET, pid),
            presetRepository.findTreeSelections(DimensionScope.PRESET, pid),
            presetRepository.findExclusions(DimensionScope.PRESET, pid)
        )
        assertEquals("写入 AURA_BOOST 行不应污染用途维度读取", before, after)

        val auraItems = presetRepository.findItems(DimensionScope.PRESET, pid)
            .filter { it.dimension == Dimension.AURA_BOOST }
        assertEquals("AURA_BOOST 行应恰好一行", 1, auraItems.size)
        assertEquals("行应使用整包哨兵", Dimension.AURA_ALL_TAGS, auraItems.first().purposeTag)
    }

    // ── helpers ──

    private fun createEnabledDeck() {
        groupRepository.saveManager(
            CardManagerEntity(id = deckId, name = deckId, sourceFile = "$deckId.cardgroup", enabled = true)
        )
    }

    private fun savePreset(json: String): String {
        val r = call("save_strategy_preset", json)
        assertFalse("保存预设应成功: ${r.contentJson}", r.isError)
        val id = mapper.readTree(r.contentJson).get("presetId").asText()
        presetIds += id
        return id
    }

    private fun effectiveAuraIds(): List<String> = auraProvider.findAll().map { it.id }

    /**
     * 建一条光环行（`managerId = null` ⇒ 全局行 / 非空 ⇒ 私有行）。
     *
     * 直接走 repository（**有意绕开** `save_aura_boost` 的"条件树必须存在"校验）——
     * 本类验证的是**供给过滤口径**，不是光环创建路径；条件树内容不参与过滤。
     */
    private fun createAura(id: String, score: Double, managerId: String?): String {
        auraRepository.save(
            AuraBoostEntity(
                id = id,
                name = id,
                conditionId = "${id}_cond",
                targetConditionId = "${id}_target",
                score = score,
                managerId = managerId,
                enabled = true
            )
        )
        auraIds += id
        return id
    }
}
