package lin.mcp

import lin.bean.usePlan.DefaultPurposeTagIntentRuleProvider
import lin.repository.card_purpose.PurposeTagRuleRepository
import lin.serviceLoader.provider.PurposeTagIntentRuleProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.koin.core.context.GlobalContext

/**
 * T-TG-007/008：用途意图规则落库 + SPI 通道 + 降级一致性。
 *
 * 验证：
 * ① **种子与内置硬编码逐字一致**（防漂移）—— 降级回落时行为绝不能变，
 *   这条断言就是"两张表必须同步"的守门人；
 * ② SPI 通道拿到的 Sqlite 实现，读出的规则与硬编码一致（落库未改变行为）；
 * ③ **FINISH 无规则**（D-TG-003 核心语义）—— 它必须缺席，否则会以默认 priority=100 参与
 *   `UseIntentDeriver` 的 priority 选优，与 GREED(100) 平手时 stage 不确定地在 SETUP/GENERAL 间跳。
 */
class PurposeTagRuleProviderTest : McpTestEnv() {

    private val hardcoded by lazy {
        DefaultPurposeTagIntentRuleProvider().rules().associateBy { it.tagId.value }
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

    @Test
    fun `SPI 通道读出的规则与硬编码一致`() {
        val fromDb = GlobalContext.get().get<PurposeTagIntentRuleProvider>().rules()
            .associateBy { it.tagId.value }

        assertEquals("落库后规则集合不应变化", hardcoded.keys, fromDb.keys)
        hardcoded.forEach { (tagId, rule) ->
            val actual = fromDb.getValue(tagId)
            assertEquals("$tagId.defaultStage", rule.defaultStage, actual.defaultStage)
            assertEquals("$tagId.defaultOrderWeight", rule.defaultOrderWeight, actual.defaultOrderWeight, 0.0)
            assertEquals("$tagId.priority", rule.priority, actual.priority)
            assertEquals("$tagId.N", rule.defaultSurplusIdleThreshold, actual.defaultSurplusIdleThreshold)
            assertEquals("$tagId.replan", rule.defaultReplanAfterUse, actual.defaultReplanAfterUse)
        }
    }

    @Test
    fun `FINISH 必须无规则`() {
        assertFalse("FINISH 不应有规则条目（无规则 = 不参与优先级选优）", hardcoded.containsKey("FINISH"))
        val fromDb = GlobalContext.get().get<PurposeTagIntentRuleProvider>().rules()
            .map { it.tagId.value }
        assertFalse("FINISH 在库中也不应有规则条目", fromDb.contains("FINISH"))
    }
}
