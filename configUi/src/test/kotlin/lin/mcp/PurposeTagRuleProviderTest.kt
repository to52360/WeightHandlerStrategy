package lin.mcp

import lin.bean.usePlan.DefaultPurposeTagIntentRuleProvider
import lin.repository.card_group.CurrentDeckContext
import lin.repository.card_group.DimensionItemResolver
import lin.repository.card_group.DimensionScope
import lin.repository.card_group.StrategyPresetRepository
import lin.repository.card_purpose.PurposeTagRuleRepository
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext

/**
 * T-TG-007/008 + T-TG-028：用途时序**声明模型**（D-TG-018）。
 *
 * 验证：
 * ① **种子与内置硬编码逐字一致**（保留的防漂移守门人）—— 全局行现在退化为「缺省值来源」，
 *   它与 `DefaultPurposeTagIntentRuleProvider` 的一致性仍然重要（引擎侧 Koin 取不到 provider 时
 *   回落的就是那份硬编码，两侧口径不能漂移）；
 * ② **种子路径产出的规则与硬编码逐字段一致**：卡组未引用预设时规则集为空，
 *   故用「引用一个显式声明 6 个用途的预设」为探针，声明只写 stage、其余字段全部回落全局行；
 * ③ **表空 = 无兜底**（T-TG-028 核心）：全局行被清空后，声明里没写的字段用内置默认，
 *   **不再**回落整套硬编码 `DefaultPurposeTagIntentRuleProvider`；
 * ④ **FINISH 无规则**（D-TG-003 核心语义）—— 它必须缺席，否则会以默认 priority=100 参与
 *   `UseIntentDeriver` 的 priority 选优，与 GREED(100) 平手时 stage 不确定地在 SETUP/GENERAL 间跳；
 * ⑤ **未声明 = 无规则**：不引用预设 / 预设未声明增量的用途，都不出现。
 */
class PurposeTagRuleProviderTest : McpTestEnv() {

    private val ruleRepository: PurposeTagRuleRepository by lazy {
        GlobalContext.get().get<PurposeTagRuleRepository>()
    }
    private val presetRepository: StrategyPresetRepository by lazy {
        GlobalContext.get().get<StrategyPresetRepository>()
    }
    private val groupRepository: lin.repository.card_group.CardGroupRepository by lazy {
        GlobalContext.get().get()
    }

    private val hardcoded by lazy {
        DefaultPurposeTagIntentRuleProvider().rules().associateBy { it.tagId.value }
    }

    private val testDeckId = "TG_028_DECK"
    private val testPresetId = "TG_028_PRESET"

    /** 本用例被清空的全局行（@After 逐行还原，防污染开发库）。 */
    private val removedGlobalRules = mutableListOf<lin.repository.card_purpose.PurposeTagRuleEntity>()

    @After
    fun cleanUp() {
        // 还原被清空的全局规则行（声明模型下这些行仍是"缺省值来源"，不能留在空态）
        removedGlobalRules.forEach { ruleRepository.save(it) }
        removedGlobalRules.clear()
        presetRepository.deletePreset(testPresetId)
        presetRepository.deleteItems(DimensionScope.CARD_GROUP, testDeckId)
        groupRepository.deleteManager(testDeckId)
    }

    /** 手工组装 provider（不经 Koin，便于注入"当前卡组"与临时替库）。 */
    private fun provider() = lin.ui.card_purpose.SqlitePurposeTagIntentRuleProvider(
        repository = ruleRepository,
        presetRepository = presetRepository,
        currentDeck = CurrentDeckContext(groupRepository),
        resolver = DimensionItemResolver()
    )

    /**
     * 建一个 enabled 卡组（直接走 repository，避开 `saveManager` 的单活联动）。
     *
     * ⚠️ `saveManager` 的 INSERT/UPSERT **不含 `preset_id` 列** ⇒ 预设引用必须另走
     * [lin.repository.card_group.CardGroupRepository.updateManagerPreset]。
     */
    private fun createEnabledDeck(presetId: String?) {
        groupRepository.saveManager(
            lin.repository.card_group.CardManagerEntity(
                id = testDeckId, name = testDeckId,
                sourceFile = "$testDeckId.cardgroup", enabled = true
            )
        )
        groupRepository.updateManagerPreset(testDeckId, presetId)
    }

    /** 建一个预设，按 [declaredTags] 逐用途声明（只写 stage，其余字段留给全局行兜底）。 */
    private fun saveDeclaringPreset(vararg declaredTags: String) {
        presetRepository.savePreset(
            lin.repository.card_group.StrategyPresetEntity(
                id = testPresetId, name = testPresetId, description = null, createdAt = null
            )
        )
        presetRepository.replaceTimings(
            DimensionScope.PRESET, testPresetId,
            declaredTags.associateWith {
                lin.repository.card_group.TimingOverride(defaultStage = hardcoded.getValue(it).defaultStage.name)
            }
        )
    }

    @Test
    fun `种子规则与内置硬编码逐字一致`() {
        val seed = PurposeTagRuleRepository.BUILTIN_RULES.associateBy { it.tagId }
        assertEquals("tag 集合应一致（FINISH 两侧都不应有）", hardcoded.keys, seed.keys)

        hardcoded.forEach { (tagId, rule) ->
            val s = seed.getValue(tagId)
            assertEquals("$tagId.defaultStage", rule.defaultStage.name, s.defaultStage)
            assertEquals("$tagId.defaultOrderWeight", rule.defaultOrderWeight, s.defaultOrderWeight, 0.0)
            assertEquals("$tagId.priority", rule.priority, s.priority)
            assertEquals("$tagId.N", rule.defaultSurplusIdleThreshold, s.defaultSurplusIdleThreshold)
            assertEquals("$tagId.replan", rule.defaultReplanAfterUse, s.defaultReplanAfterUse)
        }
    }

    /**
     * 种子路径回归：预设声明全部 6 个用途（只写 stage），其余字段回落全局行
     * ⇒ 产出规则应与内置硬编码逐字段一致（证明「全局行 → 缺省值来源」未改变既有数据下的行为）。
     */
    @Test
    fun `引用预设且仅声明阶段时其余字段回落全局规则行`() {
        createEnabledDeck(testPresetId)
        saveDeclaringPreset(*hardcoded.keys.toTypedArray())

        val fromDb = provider().rules().associateBy { it.tagId.value }
        assertEquals("声明了 6 个用途 ⇒ 应有 6 条规则", hardcoded.keys, fromDb.keys)
        hardcoded.forEach { (tagId, rule) ->
            val actual = fromDb.getValue(tagId)
            assertEquals("$tagId.defaultStage", rule.defaultStage, actual.defaultStage)
            assertEquals("$tagId.defaultOrderWeight", rule.defaultOrderWeight, actual.defaultOrderWeight, 0.0)
            assertEquals("$tagId.priority", rule.priority, actual.priority)
            assertEquals("$tagId.N", rule.defaultSurplusIdleThreshold, actual.defaultSurplusIdleThreshold)
            assertEquals("$tagId.replan", rule.defaultReplanAfterUse, actual.defaultReplanAfterUse)
        }
    }

    /**
     * T-TG-028 核心：**表空 = 无兜底**（此前是"回落整套硬编码 + warn"，正是要消灭的隐式兜底）。
     *
     * 清空全局行后，声明里没写的字段取**内置默认**（`stage = GENERAL` / `priority = 100` / N = null），
     * **不是**硬编码里 CLEAN 的 MID/300/1 —— 断言必须落在"声明没写的字段"上才验得出这个差别。
     */
    @Test
    fun `全局规则表为空时不再回落硬编码而是用内置默认`() {
        createEnabledDeck(testPresetId)
        // 只声明 N（刻意不写 stage / priority）⇒ 这两项分别验"内置默认"与"不是硬编码"
        presetRepository.savePreset(
            lin.repository.card_group.StrategyPresetEntity(
                id = testPresetId, name = testPresetId, description = null, createdAt = null
            )
        )
        presetRepository.replaceTimings(
            DimensionScope.PRESET, testPresetId,
            mapOf(
                "CLEAN" to lin.repository.card_group.TimingOverride(
                    surplusIdleThreshold = lin.repository.card_group.ThresholdPatch(
                        7
                    )
                )
            )
        )

        // 清空全局行（记录后还原）
        val all = ruleRepository.findAll()
        assertTrue("前置：库里应本来有全局规则行", all.isNotEmpty())
        removedGlobalRules.addAll(all)
        all.forEach { ruleRepository.deleteByTagId(it.tagId) }

        val clean = provider().rules().single { it.tagId.value == "CLEAN" }
        assertEquals(
            "表空时不得回落硬编码（CLEAN 硬编码 stage 是 MID）",
            lin.bean.usePlan.UseStage.GENERAL,
            clean.defaultStage
        )
        assertEquals("表空时 priority 用内置默认 100（硬编码是 300）", 100, clean.priority)
        assertEquals("声明里写了的 N 应生效（不回落任何兜底）", 7, clean.defaultSurplusIdleThreshold)
    }

    /** 不引用预设 = 合法终态 ⇒ **空规则集**（D-TG-018：无隐式作用）。 */
    @Test
    fun `不引用预设时返回空规则集`() {
        createEnabledDeck(presetId = null)
        saveDeclaringPreset("CLEAN")

        assertTrue(
            "未引用预设 ⇒ 一条声明都没有 ⇒ 不得有任何规则",
            provider().rules().isEmpty()
        )
    }

    /** 未声明的用途不产出规则（声明模型：未声明 = 无规则 = 不参与 priority 选优）。 */
    @Test
    fun `未声明的用途不产出规则`() {
        createEnabledDeck(testPresetId)
        saveDeclaringPreset("CLEAN")

        val tags = provider().rules().map { it.tagId.value }
        assertEquals(listOf("CLEAN"), tags)
    }

    @Test
    fun `FINISH 必须无规则`() {
        assertFalse("FINISH 不应有全局规则条目（无规则 = 不参与优先级选优）", hardcoded.containsKey("FINISH"))
        assertNull(
            "FINISH 在库中也不应有规则条目",
            ruleRepository.findByTagId("FINISH")
        )
        assertFalse(
            "FINISH 不应出现在规则集里",
            provider().rules().any { it.tagId.value == "FINISH" }
        )
    }
}
