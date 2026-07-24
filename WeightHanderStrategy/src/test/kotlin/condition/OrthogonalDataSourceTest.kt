package condition

import club.xiaojiawei.hsscriptcardsdk.enums.CardRaceEnum
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import lin.bean.ComboCard
import lin.rule.condition.*
import lin.rule.context.RuleContext
import lin.rule.handler.EvalOutcome
import lin.rule.handler.GuardCompiler
import lin.rule.handler.LeafLogicAssembler
import lin.rule.handler.ScoreCompiler
import lin.rule.orthogonal.*
import lin.rule.score.ScoreEffect
import lin.rule.tree.*
import lin.serviceLoader.provider.ConditionRegistrationProvider
import lin.serviceLoader.provider.ConditionTreeConfigProvider
import org.junit.After
import org.junit.Before
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.*

class OrthogonalDataSourceTest {

    @Before
    fun setUp() {
        startKoin {
            modules(module {
                single {
                    lin.rule.score.ScoreOperatorRegistry(
                        listOf(lin.rule.score.DefaultScoreOperatorsProvider())
                    )
                }
            })
        }
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    private val intCondition = ConditionBuilder.scalar(ConditionType.IntType)
        .id("max_cost")
        .metadata("费用不高于")
        .field("maxCost", "费用上限")
        .factory { maxCost ->
            { maxCost <= 3 }
        }
        .build()

    private val listCondition = ConditionBuilder.list(ConditionType.StringType)
        .id("race_whitelist")
        .metadata("种族白名单")
        .field("raceIds", "种族") { select("race") }
        .factory { raceIds ->
            { raceIds.contains("BEAST") }
        }
        .build()

    private val objectMapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
    private val assembler = PipelineAssembler(
        dataSources = mapOf(
            "me_board_cards" to MeBoardCardsSource,
            "rival_board_cards" to RivalBoardCardsSource,
            "board_cards" to BoardCardsSource,
            "hand_cards" to HandCardsSource
        ),
        transforms = mapOf(
            "race_filter" to RaceFilterTransform,
            "count_projection" to CountProjectionTransform
        ),
        operators = mapOf("gte" to GreaterThanOrEqualOp, "contains_race" to ContainsRaceOp),
        objectMapper = objectMapper
    )

    private val registry = ConditionRegistry(
        providers = listOf(
            object : ConditionRegistrationProvider {
                override fun getConditionRegistrations(): Collection<ConditionRegistration<*>> {
                    return listOf(intCondition, listCondition)
                }
            }
        ),
        pipelineAssembler = assembler
    )

    private val koinScoreOperatorRegistry: lin.rule.score.ScoreOperatorRegistry by lazy {
        lin.rule.score.ScoreOperatorRegistry(
            listOf(lin.rule.score.DefaultScoreOperatorsProvider())
        )
    }

    private fun createLeafLogicAssembler(
        conditionTreeProviders: List<ConditionTreeConfigProvider> = emptyList()
    ): LeafLogicAssembler {
        val guardCompiler = GuardCompiler(registry, conditionTreeProviders, assembler)
        val scoreCompiler = ScoreCompiler(
            ruleRegistry = lin.rule.registry.RuleRegistry(emptyList()),
            assembler = assembler,
            scoreOperatorRegistry = koinScoreOperatorRegistry
        )
        return LeafLogicAssembler(guardCompiler, scoreCompiler)
    }

    @Test
    fun `registry builds pipeline condition logic`() {
        // me_board_cards -> count_projection -> gte(threshold=2)
        val payload = ConditionPayload.PipelineRef(
            sourceId = "me_board_cards",
            transforms = listOf(
                TransformCall("count_projection")
            ),
            operatorId = "gte",
            operatorArgs = mapOf("threshold" to 2),
            refId = "board_minions_count_gte_2"
        )
        val logic = registry.build(payload)

        // Default mock war info has 5 cards in hand, 0 cards on board
        assertFalse(logic(FakeRuleContext, FakeRuleEnv))

        // Test with mock cards on board
        val mockMeMinions = listOf(
            createMockCard(cardType = CardTypeEnum.MINION),
            createMockCard(cardType = CardTypeEnum.MINION)
        )
        val testWarInfo = createMockWarInfo(playCards = mockMeMinions)
        val testContext = RuleContext(ComboCard(card = createMockCard()))
        val testEnv = fakeRuleEnv(testWarInfo)

        assertTrue(logic(testContext, testEnv))
    }

    @Test
    fun `fails compilation when pipeline types are incompatible`() {
        // me_board_cards (List<Card>) -> count_projection (List<*> to Int) -> race_filter (expects List<Card>) [INCOMPATIBLE]
        val payload = ConditionPayload.PipelineRef(
            sourceId = "me_board_cards",
            transforms = listOf(
                TransformCall("count_projection"),
                TransformCall("race_filter", mapOf("race" to "PET"))
            ),
            operatorId = "gte",
            operatorArgs = mapOf("threshold" to 2),
            refId = "invalid_pipeline"
        )

        assertFailsWith<IllegalArgumentException> {
            registry.build(payload)
        }
    }

    @Test
    fun `pipeline condition with race filter works`() {
        // me_board_cards -> race_filter(PET) -> count_projection -> gte(threshold=2)
        val payload = ConditionPayload.PipelineRef(
            sourceId = "me_board_cards",
            transforms = listOf(
                TransformCall("race_filter", mapOf("race" to "PET")),
                TransformCall("count_projection")
            ),
            operatorId = "gte",
            operatorArgs = mapOf("threshold" to 2),
            refId = "board_beasts_count_gte_2"
        )
        val logic = registry.build(payload)

        val beast1 = createMockCard(cardType = CardTypeEnum.MINION, cardRace = CardRaceEnum.PET)
        val beast2 = createMockCard(cardType = CardTypeEnum.MINION, cardRace = CardRaceEnum.PET)
        val mech = createMockCard(cardType = CardTypeEnum.MINION, cardRace = CardRaceEnum.MECHANICAL)

        // 2 beasts + 1 mech -> beasts count = 2 >= 2 -> true
        val warInfo1 = createMockWarInfo(playCards = listOf(beast1, beast2, mech))
        val context1 = RuleContext(ComboCard(card = createMockCard()))
        val env1 = fakeRuleEnv(warInfo1)
        assertTrue(logic(context1, env1))

        // 1 beast + 1 mech -> beasts count = 1 >= 2 -> false
        val warInfo2 = createMockWarInfo(playCards = listOf(beast1, mech))
        val context2 = RuleContext(ComboCard(card = createMockCard()))
        val env2 = fakeRuleEnv(warInfo2)
        assertFalse(logic(context2, env2))
    }

    @Test
    fun `condition tree containing pipeline ref can instantiate evaluator leaf logic`() {
        val conditionTree = ConditionTreeConfig(
            id = "pipeline_tree",
            name = "管道条件树",
            root = LogicNode.And(
                listOf(
                    LogicNode.Leaf(
                        ConditionPayload.PipelineRef(
                            sourceId = "me_board_cards",
                            transforms = listOf(
                                TransformCall("count_projection")
                            ),
                            operatorId = "gte",
                            operatorArgs = mapOf("threshold" to 2), // Default threshold
                            refId = "occ1"
                        )
                    ),
                    LogicNode.Leaf(ConditionPayload.ConditionRef("max_cost", refId = "mc1"))
                )
            )
        )
        val conditionTreeProvider = object : ConditionTreeConfigProvider {
            override fun findById(id: String): ConditionTreeConfig? {
                return conditionTree.takeIf { it.id == id }
            }

            override fun findAll(): List<ConditionTreeConfig> {
                return listOf(conditionTree)
            }
        }
        val nodeId = "pipeline_tree_node"
        val config = EvaluatorTreeConfig(
            bindingType = EvaluatorTreeBindingType.GROUP,
            bindingIds = listOf("demo_group"),
            root = LogicNode.Leaf(EvaluatorPayload.Rule(nodeId)),
            leafConfigs = mapOf(
                nodeId to ConditionTreeLeafConfig(
                    nodeId = nodeId,
                    sourceId = "pipeline_tree",
                    scoreEffect = ScoreEffect.ConstantScore(10.0),
                    args = mapOf(
                        "occ1.threshold" to 3, // Override operatorArgs threshold to 3
                        "mc1.maxCost" to 2
                    )
                )
            )
        )

        val assembler = createLeafLogicAssembler(conditionTreeProviders = listOf(conditionTreeProvider))

        val instance = config.instantiate(
            leafBuilder = assembler::build,
            branchConditionBuilder = assembler::buildBranch
        )

        val root = instance.root as lin.rule.tree.EvaluatorInstanceNode.RuleNode

        // Test case 1: 3 minions on board, max cost = 1 -> true && true -> returns 10.0 score
        val mockMeMinions = listOf(
            createMockCard(cardType = CardTypeEnum.MINION),
            createMockCard(cardType = CardTypeEnum.MINION),
            createMockCard(cardType = CardTypeEnum.MINION)
        )
        val warInfo1 = createMockWarInfo(playCards = mockMeMinions)
        val context1 = RuleContext(ComboCard(card = createMockCard(cost = 1)))
        val env1 = fakeRuleEnv(warInfo1)
        val result1 = root.leafLogic(context1, env1)
        assertEquals(EvalOutcome.Matched(score = 10.0), result1)

        // Test case 2: 2 minions on board (threshold overridden to 3) -> false && true -> returns 0.0 (miss value)
        val warInfo2 = createMockWarInfo(playCards = mockMeMinions.take(2))
        val context2 = RuleContext(ComboCard(card = createMockCard(cost = 1)))
        val env2 = fakeRuleEnv(warInfo2)
        val result2 = root.leafLogic(context2, env2)
        assertEquals(EvalOutcome.Skipped(score = 0.0), result2)
    }

    @Test
    fun `fails fast when score pipeline source output does not match score operator`() {
        val nodeId = "invalid_source_node"
        val config = EvaluatorTreeConfig(
            bindingType = EvaluatorTreeBindingType.GROUP,
            bindingIds = listOf("demo_group"),
            root = LogicNode.Leaf(EvaluatorPayload.Rule(nodeId)),
            leafConfigs = mapOf(
                nodeId to OrthogonalRuleLeafConfig(
                    nodeId = nodeId,
                    sourceId = "orthogonal_rule",
                    scoreEffect = ScoreEffect.SourceScore(
                        sourceId = "me_board_cards",
                        transforms = emptyList(), // List<Card>
                        operatorId = "identity",  // Expects Int/Number [INCOMPATIBLE]
                        operatorArgs = emptyMap()
                    ),
                    args = mapOf("maxCost" to 2)
                )
            )
        )

        assertFailsWith<IllegalArgumentException> {
            val leafAssembler = createLeafLogicAssembler()
            config.instantiate(
                leafBuilder = leafAssembler::build,
                branchConditionBuilder = leafAssembler::buildBranch
            )
        }
    }

    @Test
    fun `score source pipeline evaluates correctly`() {
        // scoring logic: me_board_cards -> count_projection (Int) -> linear(factor=2.0, offset=1.0)
        val nodeId = "score_pipeline_node"
        val config = EvaluatorTreeConfig(
            bindingType = EvaluatorTreeBindingType.GROUP,
            bindingIds = listOf("demo_group"),
            root = LogicNode.Leaf(EvaluatorPayload.Rule(nodeId)),
            leafConfigs = mapOf(
                nodeId to OrthogonalRuleLeafConfig(
                    nodeId = nodeId,
                    sourceId = "orthogonal_rule",
                    scoreEffect = ScoreEffect.SourceScore(
                        sourceId = "me_board_cards",
                        transforms = listOf(
                            TransformCall("count_projection")
                        ),
                        operatorId = "linear",
                        operatorArgs = mapOf("factor" to 2.0, "offset" to 1.0)
                    ),
                    args = mapOf("maxCost" to 2)
                )
            )
        )

        val leafAssembler = createLeafLogicAssembler()
        val instance = config.instantiate(
            leafBuilder = leafAssembler::build,
            branchConditionBuilder = leafAssembler::buildBranch
        )

        val root = instance.root as lin.rule.tree.EvaluatorInstanceNode.RuleNode

        // 3 minions on board -> score = 3 * 2.0 + 1.0 = 7.0
        val mockMeMinions = listOf(
            createMockCard(cardType = CardTypeEnum.MINION),
            createMockCard(cardType = CardTypeEnum.MINION),
            createMockCard(cardType = CardTypeEnum.MINION)
        )
        val warInfo = createMockWarInfo(playCards = mockMeMinions)
        val context = RuleContext(ComboCard(card = createMockCard(cost = 1)))
        val env = fakeRuleEnv(warInfo)

        val result = root.leafLogic(context, env)
        assertEquals(EvalOutcome.Matched(score = 7.0), result)
    }
}
