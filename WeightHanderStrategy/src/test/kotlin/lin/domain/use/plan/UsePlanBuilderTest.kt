package lin.domain.use.plan

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import condition.createMockCard
import condition.createMockWarInfo
import condition.fakeRuleEnv
import lin.bean.CardCombinedConfig
import lin.bean.CardWeightInfo
import lin.bean.ComboCard
import lin.bean.usePlan.ConditionalStageOverride
import lin.bean.usePlan.UseIntent
import lin.bean.usePlan.UseStage
import lin.rule.condition.*
import lin.rule.handler.GuardCompiler
import lin.rule.orthogonal.CountProjectionTransform
import lin.rule.orthogonal.HandCardsSource
import lin.rule.orthogonal.LessThanOrEqualOp
import lin.rule.orthogonal.TransformCall
import lin.rule.tree.LogicNode
import lin.serviceLoader.provider.ConditionRegistrationProvider
import lin.serviceLoader.provider.ConditionTreeArgsProvider
import lin.serviceLoader.provider.ConditionTreeConfigProvider
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * conditionalStage 条件化阶段覆盖的运行期求值测试：
 * 条件树命中 → 覆盖 stage；未命中 → elseStage（null 沿用基础 UseIntent）。
 */
class UsePlanBuilderTest {

    private val objectMapper = ObjectMapper().registerKotlinModule()

    /** 构造「手牌数量 ≤ threshold」条件树，叶子 PipelineRef 自带阈值参数。 */
    private fun handCountLteTree(id: String, threshold: Int): ConditionTreeConfig = ConditionTreeConfig(
        id = id,
        name = "hand<=$threshold",
        root = LogicNode.Leaf(
            ConditionPayload.PipelineRef(
                refId = "hand_count",
                sourceId = HandCardsSource.id,
                transforms = listOf(TransformCall(CountProjectionTransform.id, emptyMap())),
                operatorId = LessThanOrEqualOp.id,
                operatorArgs = mapOf("threshold" to threshold)
            )
        )
    )

    private fun buildBuilder(
        tree: ConditionTreeConfig,
        codedConditions: Collection<ConditionRegistration<*>> = emptyList(),
        conditionArgsById: Map<String, Map<String, Any>> = emptyMap()
    ): UsePlanBuilder {
        val assembler = PipelineAssembler(
            dataSources = mapOf(HandCardsSource.id to HandCardsSource),
            transforms = mapOf(CountProjectionTransform.id to CountProjectionTransform),
            operators = mapOf(LessThanOrEqualOp.id to LessThanOrEqualOp),
            objectMapper = objectMapper
        )
        val registry = ConditionRegistry(
            providers = listOf(
                object : ConditionRegistrationProvider {
                    override fun getConditionRegistrations(): Collection<ConditionRegistration<*>> = codedConditions
                }
            ),
            pipelineAssembler = assembler
        )
        val treeProvider = object : ConditionTreeConfigProvider {
            override fun findById(id: String): ConditionTreeConfig? =
                if (id == tree.id) tree else null

            override fun findAll(): List<ConditionTreeConfig> = listOf(tree)
        }
        // 旁挂参数表（D-005）：conditionArgsById 按树 id 提供 prefixed 参数
        val argsProvider = object : ConditionTreeArgsProvider {
            override fun findById(treeId: String): Map<String, Any>? = conditionArgsById[treeId]
        }
        return UsePlanBuilder(GuardCompiler(registry, listOf(treeProvider), assembler, listOf(argsProvider)))
    }

    private fun ampCard(cs: ConditionalStageOverride): ComboCard = ComboCard(
        card = createMockCard(cardId = "TEST_AMP"),
        combinedConfig = CardCombinedConfig(
            weightInfo = CardWeightInfo(cardId = "TEST_AMP", powerWeight = 1.0),
            useIntent = UseIntent(stage = UseStage.GENERAL),
            conditionalStage = cs
        )
    )

    @Test
    fun testConditionHitUsesStage() {
        val tree = handCountLteTree("tree_lte_3", 3)
        val card = ampCard(ConditionalStageOverride(conditionId = tree.id, stage = UseStage.SETUP))
        // 手牌 2 张 → count=2 → lte(3) 命中 → stage=SETUP
        val env = fakeRuleEnv(createMockWarInfo(handCards = List(2) { createMockCard() }))
        val plan = buildBuilder(tree).build(listOf(card), env)
        assertEquals(UseStage.SETUP, plan.intents.getValue(card).stage)
    }

    @Test
    fun testConditionMissFallsBackToBaseIntent() {
        val tree = handCountLteTree("tree_lte_3", 3)
        val card = ampCard(ConditionalStageOverride(conditionId = tree.id, stage = UseStage.SETUP))
        // 手牌 5 张 → count=5 → lte(3) 未命中 → elseStage=null → 沿用基础 GENERAL
        val env = fakeRuleEnv(createMockWarInfo(handCards = List(5) { createMockCard() }))
        val plan = buildBuilder(tree).build(listOf(card), env)
        assertEquals(UseStage.GENERAL, plan.intents.getValue(card).stage)
    }

    @Test
    fun testConditionMissUsesElseStage() {
        val tree = handCountLteTree("tree_lte_3", 3)
        val card = ampCard(
            ConditionalStageOverride(conditionId = tree.id, stage = UseStage.SETUP, elseStage = UseStage.RESOURCE)
        )
        // 手牌 5 张 → 未命中 → elseStage=RESOURCE
        val env = fakeRuleEnv(createMockWarInfo(handCards = List(5) { createMockCard() }))
        val plan = buildBuilder(tree).build(listOf(card), env)
        assertEquals(UseStage.RESOURCE, plan.intents.getValue(card).stage)
    }

    @Test
    fun testNoConditionalStageKeepsBaseIntent() {
        val card = ComboCard(
            card = createMockCard(cardId = "TEST_PLAIN"),
            combinedConfig = CardCombinedConfig(
                weightInfo = CardWeightInfo(cardId = "TEST_PLAIN", powerWeight = 1.0),
                useIntent = UseIntent(stage = UseStage.SETUP)
            )
        )
        val tree = handCountLteTree("tree_lte_3", 3)
        val env = fakeRuleEnv(createMockWarInfo(handCards = List(2) { createMockCard() }))
        val plan = buildBuilder(tree).build(listOf(card), env)
        assertEquals(UseStage.SETUP, plan.intents.getValue(card).stage)
    }

    /** 构造「编码条件 maxCost ≤ N」模板树：ConditionRef 叶子，参数存旁挂表（D-005）。 */
    private fun handCostLteTree(id: String): ConditionTreeConfig = ConditionTreeConfig(
        id = id,
        name = "cost<=N",
        root = LogicNode.Leaf(ConditionPayload.ConditionRef(conditionId = "hand_cost", refId = "hc"))
    )

    @Test
    fun testConditionRefArgsFromSideTable() {
        // 编码条件：手牌费用上限，参数 maxCost 由旁挂参数表提供（消费方只存树 id）
        val coded = ConditionBuilder.scalar(ConditionType.IntType)
            .id("hand_cost")
            .metadata("手牌费用上限")
            .field("maxCost", "费用上限")
            .factory { maxCost: Int -> { maxCost <= 3 } }
            .build()
        val tree = handCostLteTree("tree_cost")
        // 旁表 args 以 "refId.propertyName" 前缀存储（与评估树 ConditionTreeLeafConfig.args 同构）
        val builder = buildBuilder(
            tree,
            codedConditions = listOf(coded),
            conditionArgsById = mapOf("tree_cost" to mapOf("hc.maxCost" to 2))
        )
        val card = ampCard(ConditionalStageOverride(conditionId = tree.id, stage = UseStage.SETUP))
        val env = fakeRuleEnv(createMockWarInfo(handCards = List(2) { createMockCard() }))
        val plan = builder.build(listOf(card), env)
        assertEquals(UseStage.SETUP, plan.intents.getValue(card).stage)
    }

    @Test
    fun testConditionRefArgsMissFallsBack() {
        val coded = ConditionBuilder.scalar(ConditionType.IntType)
            .id("hand_cost")
            .metadata("手牌费用上限")
            .field("maxCost", "费用上限")
            .factory { maxCost: Int -> { maxCost <= 3 } }
            .build()
        val tree = handCostLteTree("tree_cost")
        val builder = buildBuilder(
            tree,
            codedConditions = listOf(coded),
            conditionArgsById = mapOf("tree_cost" to mapOf("hc.maxCost" to 5))
        )
        // maxCost=5 → 未命中 → elseStage=null → 沿用基础 GENERAL
        val card = ampCard(
            ConditionalStageOverride(
                conditionId = tree.id,
                stage = UseStage.SETUP
            )
        )
        val env = fakeRuleEnv(createMockWarInfo(handCards = List(2) { createMockCard() }))
        val plan = builder.build(listOf(card), env)
        assertEquals(UseStage.GENERAL, plan.intents.getValue(card).stage)
    }
}
