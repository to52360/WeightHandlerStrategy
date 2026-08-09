package lin.rule.handler

import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import condition.createMockCard
import condition.createMockWarInfo
import condition.fakeRuleEnv
import lin.bean.AuraBoostConfig
import lin.bean.CardCombinedConfig
import lin.bean.CardWeightInfo
import lin.bean.ComboCard
import lin.domain.WarInfo
import lin.rule.condition.*
import lin.rule.orthogonal.*
import lin.rule.tree.LogicNode
import lin.serviceLoader.provider.AuraBoostConfigProvider
import lin.serviceLoader.provider.ConditionRegistrationProvider
import lin.serviceLoader.provider.ConditionTreeConfigProvider
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AuraBoost push 广播评分端到端验证（aura-boost P-5）。
 *
 * 场景驱动：莱妮莎（solo_lynessa 组）在场 → ≤2费法术卡 +8。
 * 验证不双倍计分：AuraBoost 是唯一光环加分通道（D-004），评估树已删莱妮莎条件分支，
 * 此处验证 activeScore 本身 per-card 判定正确且只加一次分。
 */
class AuraBoostEvaluatorTest {

    private val objectMapper = ObjectMapper().registerKotlinModule()

    // ── 两棵条件树（与 MCP 落库配置一致）──

    /** 触发树：莱妮莎在场 = me_combo_cards → group_filter(solo_lynessa) → count → gte(1) */
    private val lynessaPresentTree = ConditionTreeConfig(
        id = "boost_lynessa_present",
        name = "boost_lynessa_present",
        root = LogicNode.Leaf(
            ConditionPayload.PipelineRef(
                refId = "r1",
                sourceId = MeComboCardsSource.id,
                transforms = listOf(
                    TransformCall(GroupFilterTransform.id, mapOf("groupId" to "tizhhq9c")),
                    TransformCall(CountProjectionTransform.id, emptyMap())
                ),
                operatorId = GreaterThanOrEqualOp.id,
                operatorArgs = mapOf("threshold" to 1)
            )
        )
    )

    /** 受益树：≤2费法术 = And(is_card_type(SPELL), cost ≤ 2) */
    private val spellCostLte2Tree = ConditionTreeConfig(
        id = "boost_spell_cost_lte2",
        name = "boost_spell_cost_lte2",
        root = LogicNode.And(
            children = listOf(
                LogicNode.Leaf(
                    ConditionPayload.PipelineRef(
                        refId = "t1",
                        sourceId = EvaluatingCardSource.id,
                        transforms = listOf(TransformCall(ToCardTransform.id, emptyMap())),
                        operatorId = IsCardTypeOp.id,
                        operatorArgs = mapOf("targetType" to "SPELL")
                    )
                ),
                LogicNode.Leaf(
                    ConditionPayload.PipelineRef(
                        refId = "t2",
                        sourceId = EvaluatingCardSource.id,
                        transforms = listOf(TransformCall(EvaluatingCardCostTransform.id, emptyMap())),
                        operatorId = LessThanOrEqualOp.id,
                        operatorArgs = mapOf("threshold" to 2)
                    )
                )
            )
        )
    )

    private fun buildEvaluator(): AuraBoostEvaluator {
        val assembler = PipelineAssembler(
            dataSources = mapOf(
                MeComboCardsSource.id to MeComboCardsSource,
                EvaluatingCardSource.id to EvaluatingCardSource
            ),
            transforms = mapOf(
                GroupFilterTransform.id to GroupFilterTransform,
                CountProjectionTransform.id to CountProjectionTransform,
                ToCardTransform.id to ToCardTransform,
                EvaluatingCardCostTransform.id to EvaluatingCardCostTransform
            ),
            operators = mapOf(
                GreaterThanOrEqualOp.id to GreaterThanOrEqualOp,
                IsCardTypeOp.id to IsCardTypeOp,
                LessThanOrEqualOp.id to LessThanOrEqualOp
            ),
            objectMapper = objectMapper
        )
        val registry = ConditionRegistry(
            providers = listOf(
                object : ConditionRegistrationProvider {
                    override fun getConditionRegistrations() = emptyList<ConditionRegistration<*>>()
                }
            ),
            pipelineAssembler = assembler
        )
        val treeProvider = object : ConditionTreeConfigProvider {
            override fun findById(id: String): ConditionTreeConfig? = when (id) {
                lynessaPresentTree.id -> lynessaPresentTree
                spellCostLte2Tree.id -> spellCostLte2Tree
                else -> null
            }

            override fun findAll(): List<ConditionTreeConfig> = listOf(lynessaPresentTree, spellCostLte2Tree)
        }
        val boost = AuraBoostConfig(
            id = "boost1",
            name = "boost_lynessa_spell_lte2",
            conditionId = lynessaPresentTree.id,
            targetConditionId = spellCostLte2Tree.id,
            score = 8.0
        )
        return AuraBoostEvaluator(
            guardCompiler = GuardCompiler(registry, listOf(treeProvider), assembler),
            providers = listOf(
                object : AuraBoostConfigProvider {
                    override fun findAll(): List<AuraBoostConfig> = listOf(boost)
                }
            )
        )
    }

    // ── helpers ──

    /** 莱妮莎在场模拟：WarInfo.playComboCards 含 VAC_507（groupIds 含 solo_lynessa） */
    private fun warInfoWithLynessaOnBoard(): WarInfo {
        val base = createMockWarInfo()
        val lynessa = ComboCard(
            card = createMockCard(cardId = "VAC_507", cardType = CardTypeEnum.MINION),
            combinedConfig = CardCombinedConfig(
                weightInfo = CardWeightInfo(cardId = "VAC_507", powerWeight = 0.0),
                groupIds = setOf("tizhhq9c")
            )
        )
        return object : WarInfo by base {
            override val playComboCards: List<ComboCard> = listOf(lynessa)
        }
    }

    private fun warInfoWithoutLynessa(): WarInfo = createMockWarInfo()

    private fun evaluateCard(warInfo: WarInfo, cardId: String, cardType: CardTypeEnum, cost: Int): Double {
        val card = ComboCard(
            card = createMockCard(cardId = cardId, cardType = cardType, cost = cost),
            combinedConfig = CardCombinedConfig(
                weightInfo = CardWeightInfo(cardId = cardId, powerWeight = 0.0)
            )
        )
        return buildEvaluator().activeScore(card, fakeRuleEnv(warInfo))
    }

    // ── 用例 ──

    @Test
    fun `莱妮莎在场时 2费法术 +8`() {
        assertEquals(8.0, evaluateCard(warInfoWithLynessaOnBoard(), "BT_025", CardTypeEnum.SPELL, 2), 1e-9)
    }

    @Test
    fun `莱妮莎在场时 0费法术 +8`() {
        assertEquals(8.0, evaluateCard(warInfoWithLynessaOnBoard(), "BT_025", CardTypeEnum.SPELL, 0), 1e-9)
    }

    @Test
    fun `莱妮莎在场时 3费法术不加分`() {
        assertEquals(0.0, evaluateCard(warInfoWithLynessaOnBoard(), "GDB_137", CardTypeEnum.SPELL, 3), 1e-9)
    }

    @Test
    fun `莱妮莎在场时 2费随从不加分 target类型过滤生效`() {
        assertEquals(0.0, evaluateCard(warInfoWithLynessaOnBoard(), "TEST_MINION", CardTypeEnum.MINION, 2), 1e-9)
    }

    @Test
    fun `莱妮莎不在场时 2费法术不加分`() {
        assertEquals(0.0, evaluateCard(warInfoWithoutLynessa(), "BT_025", CardTypeEnum.SPELL, 2), 1e-9)
    }

    @Test
    fun `莱妮莎不在场时 0费法术不加分`() {
        assertEquals(0.0, evaluateCard(warInfoWithoutLynessa(), "BT_025", CardTypeEnum.SPELL, 0), 1e-9)
    }
}
