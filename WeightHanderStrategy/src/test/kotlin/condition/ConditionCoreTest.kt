package condition

import lin.rule.condition.*
import lin.rule.handler.EvalOutcome
import lin.rule.parse.FieldType
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

class ConditionCoreTest {

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
    private val assembler = lin.rule.condition.PipelineAssembler(
        dataSources = emptyMap(),
        transforms = emptyMap(),
        operators = emptyMap(),
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

    private fun createLeafLogicAssembler(
        conditionTreeProviders: List<ConditionTreeConfigProvider> = emptyList()
    ): lin.rule.handler.LeafLogicAssembler {
        val guardCompiler = lin.rule.handler.GuardCompiler(registry, conditionTreeProviders, assembler)
        val scoreCompiler = lin.rule.handler.ScoreCompiler(
            ruleRegistry = lin.rule.registry.RuleRegistry(emptyList()),
            assembler = assembler,
            scoreOperatorRegistry = lin.rule.score.ScoreOperatorRegistry(
                listOf(lin.rule.score.DefaultScoreOperatorsProvider())
            )
        )
        return lin.rule.handler.LeafLogicAssembler(guardCompiler, scoreCompiler)
    }

    @Test
    fun `scalar field exposes rule field spec`() {
        val conditionMeta = registry.metadataList().first { it.conditionId == "max_cost" }
        val field = conditionMeta.fields.single()

        assertEquals("maxCost", field.propertyName)
        assertEquals("费用上限", field.name)
        assertEquals(FieldType.IntType, field.fieldSpec.typeStruct)
    }

    @Test
    fun `list select field exposes list of select type`() {
        val conditionMeta = registry.metadataList().first { it.conditionId == "race_whitelist" }
        val field = conditionMeta.fields.single()

        assertEquals("raceIds", field.propertyName)
        assertEquals(
            FieldType.ListType(
                FieldType.SelectType("race", FieldType.StringType)
            ),
            field.fieldSpec.typeStruct
        )
    }

    @Test
    fun `registry builds scalar condition logic`() {
        val logic = registry.build("max_cost", mapOf("maxCost" to 2))

        assertTrue(logic(FakeRuleContext, FakeRuleEnv))
        assertFalse(registry.build("max_cost", mapOf("maxCost" to 5))(FakeRuleContext, FakeRuleEnv))
    }

    @Test
    fun `registry builds list condition logic`() {
        val logic = registry.build("race_whitelist", mapOf("raceIds" to listOf("MECH", "BEAST")))

        assertTrue(logic(FakeRuleContext, FakeRuleEnv))
        assertFalse(registry.build("race_whitelist", mapOf("raceIds" to listOf("MECH")))(FakeRuleContext, FakeRuleEnv))
    }

    @Test
    fun `registry rejects unknown condition id`() {
        assertFailsWith<ConditionBuildException> {
            registry.build("missing_condition", mapOf("value" to 1))
        }
    }

    @Test
    fun `registry rejects missing scalar arg`() {
        assertFailsWith<ConditionBuildException> {
            registry.build("max_cost", emptyMap())
        }
    }

    @Test
    fun `registry rejects invalid scalar arg type`() {
        assertFailsWith<ConditionBuildException> {
            registry.build("max_cost", mapOf("maxCost" to "3"))
        }
    }

    @Test
    fun `registry rejects invalid list arg type`() {
        assertFailsWith<ConditionBuildException> {
            registry.build("race_whitelist", mapOf("raceIds" to "BEAST"))
        }
    }

    @Test
    fun `condition tree and uses all children`() {
        val tree: ConditionNode = LogicNode.And(
            listOf(
                LogicNode.Leaf(ConditionPayload.ConditionRef("max_cost", refId = "mc1", args = mapOf("maxCost" to 2))),
                LogicNode.Leaf(
                    ConditionPayload.ConditionRef(
                        "race_whitelist",
                        refId = "rw1",
                        args = mapOf("raceIds" to listOf("BEAST"))
                    )
                )
            )
        )

        assertTrue(tree.compile(registry)(FakeRuleContext, FakeRuleEnv))
    }

    @Test
    fun `condition tree or returns true when one child matches`() {
        val tree: ConditionNode = LogicNode.Or(
            listOf(
                LogicNode.Leaf(ConditionPayload.ConditionRef("max_cost", refId = "mc1", args = mapOf("maxCost" to 5))),
                LogicNode.Leaf(
                    ConditionPayload.ConditionRef(
                        "race_whitelist",
                        refId = "rw1",
                        args = mapOf("raceIds" to listOf("BEAST"))
                    )
                )
            )
        )

        assertTrue(tree.compile(registry)(FakeRuleContext, FakeRuleEnv))
    }

    @Test
    fun `condition tree not flips child result`() {
        val tree: ConditionNode =
            LogicNode.Not(
                LogicNode.Leaf(ConditionPayload.ConditionRef("max_cost", refId = "mc1", args = mapOf("maxCost" to 5)))
            )

        assertTrue(tree.compile(registry)(FakeRuleContext, FakeRuleEnv))
    }

    @Test
    fun `condition tree branch chooses true path`() {
        val tree: ConditionNode =
            LogicNode.Branch(
                payload = ConditionPayload.ConditionRef("max_cost", refId = "mc1", args = mapOf("maxCost" to 2)),
                onTrue = LogicNode.Leaf(
                    ConditionPayload.ConditionRef(
                        "race_whitelist",
                        refId = "rw1",
                        args = mapOf("raceIds" to listOf("BEAST"))
                    )
                ),
                onFalse = LogicNode.Leaf(
                    ConditionPayload.ConditionRef(
                        "race_whitelist",
                        refId = "rw2",
                        args = mapOf("raceIds" to listOf("MECH"))
                    )
                )
            )

        assertTrue(tree.compile(registry)(FakeRuleContext, FakeRuleEnv))
    }

    @Test
    fun `condition tree branch chooses false path`() {
        val tree: ConditionNode =
            LogicNode.Branch(
                payload = ConditionPayload.ConditionRef("max_cost", refId = "mc1", args = mapOf("maxCost" to 5)),
                onTrue = LogicNode.Leaf(
                    ConditionPayload.ConditionRef(
                        "race_whitelist",
                        refId = "rw1",
                        args = mapOf("raceIds" to listOf("BEAST"))
                    )
                ),
                onFalse = LogicNode.Not(
                    LogicNode.Leaf(
                        ConditionPayload.ConditionRef(
                            "race_whitelist",
                            refId = "rw2",
                            args = mapOf("raceIds" to listOf("MECH"))
                        )
                    )
                )
            )

        assertTrue(tree.compile(registry)(FakeRuleContext, FakeRuleEnv))
    }

    @Test
    fun `condition leaf config can instantiate evaluator leaf logic`() {
        val nodeId = "condition_node_1"
        val config = EvaluatorTreeConfig(
            bindingType = EvaluatorTreeBindingType.GROUP,
            bindingIds = listOf("demo_group"),
            root = LogicNode.Leaf(EvaluatorPayload.Rule(nodeId)),
            leafConfigs = mapOf(
                nodeId to ConditionLeafConfig(
                    nodeId = nodeId,
                    sourceId = "max_cost",
                    scoreEffect = ScoreEffect.ConstantScore(7.0),
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
        val result = root.leafLogic(FakeRuleContext, FakeRuleEnv)
        assertEquals(EvalOutcome.Matched(score = 7.0), result)
    }

    @Test
    fun `condition tree source can instantiate evaluator leaf logic`() {
        // 条件树是纯模板：只绑 conditionId + refId，不填 args
        val conditionTree = ConditionTreeConfig(
            id = "low_cost_beast",
            name = "低费野兽",
            root = LogicNode.And(
                listOf(
                    LogicNode.Leaf(ConditionPayload.ConditionRef("max_cost", refId = "mc1")),
                    LogicNode.Leaf(ConditionPayload.ConditionRef("race_whitelist", refId = "rw1"))
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
        val nodeId = "condition_tree_node_1"
        val config = EvaluatorTreeConfig(
            bindingType = EvaluatorTreeBindingType.GROUP,
            bindingIds = listOf("demo_group"),
            root = LogicNode.Leaf(EvaluatorPayload.Rule(nodeId)),
            leafConfigs = mapOf(
                nodeId to ConditionTreeLeafConfig(
                    nodeId = nodeId,
                    sourceId = "low_cost_beast",
                    scoreEffect = ScoreEffect.ConstantScore(9.0),
                    // args 由评估树侧以 "refId.propertyName" 前缀格式填入
                    args = mapOf(
                        "mc1.maxCost" to 2,
                        "rw1.raceIds" to listOf("BEAST")
                    )
                )
            )
        )

        val leafAssembler = createLeafLogicAssembler(conditionTreeProviders = listOf(conditionTreeProvider))
        val instance = config.instantiate(
            leafBuilder = leafAssembler::build,
            branchConditionBuilder = leafAssembler::buildBranch
        )

        val root = instance.root as lin.rule.tree.EvaluatorInstanceNode.RuleNode
        val result = root.leafLogic(FakeRuleContext, FakeRuleEnv)
        assertEquals(EvalOutcome.Matched(score = 9.0), result)
    }
}

