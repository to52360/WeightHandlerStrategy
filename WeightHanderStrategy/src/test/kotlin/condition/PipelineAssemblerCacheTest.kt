package condition

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import lin.rule.condition.ConditionPayload
import lin.rule.condition.PipelineAssembler
import lin.rule.orthogonal.TransformCall
import lin.rule.orthogonal.dataSource
import lin.rule.orthogonal.operator
import lin.rule.orthogonal.transform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

data class DummyOpParams(val value: Int = 0)

class PipelineAssemblerCacheTest {

    private val objectMapper = ObjectMapper().registerKotlinModule()

    @Test
    fun testCrossCardDisabledDirectEvaluation() {
        var sourceCalls = 0

        val dummySource = dataSource<Int>("src", "Src") {
            sourceCalls++
            42
        }
        val dummyOperator = operator<Int, DummyOpParams>("gte", "GTE") { input, param -> input >= param.value }

        val assembler = PipelineAssembler(
            dataSources = mapOf(dummySource.id to dummySource),
            transforms = emptyMap(),
            operators = mapOf(dummyOperator.id to dummyOperator),
            objectMapper = objectMapper
        )

        // crossCard 默认为 false (不开启缓存，直连评估)
        val refA = ConditionPayload.PipelineRef(
            refId = "pipeline_alpha", sourceId = "src", crossCard = false,
            operatorId = "gte", operatorArgs = mapOf("value" to 10)
        )
        val refB = ConditionPayload.PipelineRef(
            refId = "pipeline_beta", sourceId = "src", crossCard = false,
            operatorId = "gte", operatorArgs = mapOf("value" to 10)
        )

        val logicA = assembler.assemble(refA)
        val logicB = assembler.assemble(refB)
        val env = fakeRuleEnv(defaultMockWarInfo)

        logicA(FakeRuleContext, env)
        assertEquals(1, sourceCalls)

        // crossCard = false 下，不进缓存，直连求值触发第 2 次计算
        logicB(FakeRuleContext, env)
        assertEquals(2, sourceCalls)
    }

    @Test
    fun testFullPipelineContentHashCacheHitWhenCrossCardEnabled() {
        var sourceCalls = 0
        var transformCalls = 0

        val dummySource = dataSource<Int>("counter_source", "Counter Source") {
            sourceCalls++
            42
        }

        val dummyTransform = transform<Int, Int>("add_transform", "Add Transform") { input, args ->
            transformCalls++
            val amount = (args["amount"] as? Number)?.toInt() ?: 0
            input + amount
        }

        val dummyOperator = operator<Int, DummyOpParams>("gte", "GTE") { input, param -> input >= param.value }

        val assembler = PipelineAssembler(
            dataSources = mapOf(dummySource.id to dummySource),
            transforms = mapOf(dummyTransform.id to dummyTransform),
            operators = mapOf(dummyOperator.id to dummyOperator),
            objectMapper = objectMapper
        )

        // 两个内容完全一致、显式声明 crossCard = true 的 PipelineRef
        val refA = ConditionPayload.PipelineRef(
            refId = "pipeline_ref_alpha",
            sourceId = "counter_source",
            transforms = listOf(TransformCall("add_transform", mapOf("amount" to 8))),
            operatorId = "gte",
            operatorArgs = mapOf("value" to 50),
            crossCard = true
        )

        val refB = ConditionPayload.PipelineRef(
            refId = "pipeline_ref_beta", // refId 不同
            sourceId = "counter_source",
            transforms = listOf(TransformCall("add_transform", mapOf("amount" to 8))),
            operatorId = "gte",
            operatorArgs = mapOf("value" to 50),
            crossCard = true
        )

        val logicA = assembler.assemble(refA)
        val logicB = assembler.assemble(refB)

        val env = fakeRuleEnv(defaultMockWarInfo)

        // 首次评估 (Ref A)
        val resA = logicA(FakeRuleContext, env)
        assertTrue(resA)
        assertEquals(1, sourceCalls)
        assertEquals(1, transformCalls)

        // 第二次评估 (Ref B，不同 refId，crossCard = true，同一 RuleEnv)
        val resB = logicB(FakeRuleContext, env)
        assertTrue(resB)
        // 校验 100% 缓存命中，求值函数未重复执行
        assertEquals(1, sourceCalls)
        assertEquals(1, transformCalls)
    }

    @Test
    fun testPartialPipelinePrefixCacheHitWhenCrossCardEnabled() {
        var sourceCalls = 0
        var tf1Calls = 0
        var tf2Calls = 0
        var tf3Calls = 0

        val source = dataSource<Int>("src", "Src") {
            sourceCalls++
            10
        }

        val tf1 = transform<Int, Int>("tf1", "Tf1") { input, _ ->
            tf1Calls++
            input * 2
        }

        val tf2 = transform<Int, Int>("tf2", "Tf2") { input, _ ->
            tf2Calls++
            input + 5
        }

        val tf3 = transform<Int, Int>("tf3", "Tf3") { input, _ ->
            tf3Calls++
            input + 100
        }

        val op = operator<Int, DummyOpParams>("pass", "Pass") { input, _ -> input > 0 }

        val assembler = PipelineAssembler(
            dataSources = mapOf("src" to source),
            transforms = mapOf("tf1" to tf1, "tf2" to tf2, "tf3" to tf3),
            operators = mapOf("pass" to op),
            objectMapper = objectMapper
        )

        // Ref A: Src -> Tf1 -> Tf2 (crossCard = true)
        val refA = ConditionPayload.PipelineRef(
            refId = "pipe_A",
            sourceId = "src",
            transforms = listOf(TransformCall("tf1"), TransformCall("tf2")),
            operatorId = "pass",
            operatorArgs = mapOf("value" to 0),
            crossCard = true
        )

        // Ref B: Src -> Tf1 -> Tf3 (共享前半段 Src -> Tf1, crossCard = true)
        val refB = ConditionPayload.PipelineRef(
            refId = "pipe_B",
            sourceId = "src",
            transforms = listOf(TransformCall("tf1"), TransformCall("tf3")),
            operatorId = "pass",
            operatorArgs = mapOf("value" to 0),
            crossCard = true
        )

        val logicA = assembler.assemble(refA)
        val logicB = assembler.assemble(refB)
        val env = fakeRuleEnv(defaultMockWarInfo)

        logicA(FakeRuleContext, env)
        assertEquals(1, sourceCalls)
        assertEquals(1, tf1Calls)
        assertEquals(1, tf2Calls)
        assertEquals(0, tf3Calls)

        logicB(FakeRuleContext, env)
        // Src 和 Tf1 命中前缀缓存，Tf3 首次调用
        assertEquals(1, sourceCalls)
        assertEquals(1, tf1Calls)
        assertEquals(1, tf2Calls)
        assertEquals(1, tf3Calls)
    }

    @Test
    fun testDifferentArgsCacheMiss() {
        var tfCalls = 0

        val source = dataSource<Int>("src", "Src") { 100 }

        val tf = transform<Int, Int>("tf_param", "TfParam") { input, args ->
            tfCalls++
            val x = (args["x"] as Number).toInt()
            input + x
        }

        val op = operator<Int, DummyOpParams>("pass", "Pass") { _, _ -> true }

        val assembler = PipelineAssembler(
            dataSources = mapOf("src" to source),
            transforms = mapOf("tf_param" to tf),
            operators = mapOf("pass" to op),
            objectMapper = objectMapper
        )

        val refA = ConditionPayload.PipelineRef(
            refId = "p1", sourceId = "src",
            transforms = listOf(TransformCall("tf_param", mapOf("x" to 1))),
            operatorId = "pass", operatorArgs = mapOf("value" to 0),
            crossCard = true
        )
        val refB = ConditionPayload.PipelineRef(
            refId = "p2", sourceId = "src",
            transforms = listOf(TransformCall("tf_param", mapOf("x" to 2))),
            operatorId = "pass", operatorArgs = mapOf("value" to 0),
            crossCard = true
        )

        val env = fakeRuleEnv(defaultMockWarInfo)
        assembler.assemble(refA)(FakeRuleContext, env)
        assembler.assemble(refB)(FakeRuleContext, env)

        // 参数不同，Step Key 隔离，触发 2 次计算
        assertEquals(2, tfCalls)
    }

    @Test
    fun testRuleEnvIsolation() {
        var sourceCalls = 0

        val source = dataSource<Int>("src", "Src") {
            sourceCalls++
            1
        }
        val op = operator<Int, DummyOpParams>("pass", "Pass") { _, _ -> true }

        val assembler = PipelineAssembler(
            dataSources = mapOf("src" to source),
            transforms = emptyMap(),
            operators = mapOf("pass" to op),
            objectMapper = objectMapper
        )

        val ref = ConditionPayload.PipelineRef(
            refId = "p1", sourceId = "src", transforms = emptyList(),
            operatorId = "pass", operatorArgs = mapOf("value" to 0),
            crossCard = true
        )

        val logic = assembler.assemble(ref)

        val env1 = fakeRuleEnv(defaultMockWarInfo)
        val env2 = fakeRuleEnv(defaultMockWarInfo)

        logic(FakeRuleContext, env1)
        assertEquals(1, sourceCalls)

        // 不同的 RuleEnv 拥有独立的 evalCache
        logic(FakeRuleContext, env2)
        assertEquals(2, sourceCalls)
    }

    @Test
    fun testScoreCompilerPipelineCache() {
        var sourceCalls = 0
        var transformCalls = 0

        val source = dataSource<Int>("score_src", "Score Src") {
            sourceCalls++
            100
        }

        val tf = transform<Int, Int>("score_tf", "Score Tf") { input, _ ->
            transformCalls++
            input * 2
        }

        val scoreOp = lin.rule.score.scoreOperator<Int, DummyOpParams>("identity_op", "Identity Op") { input, _ ->
            input.toDouble()
        }

        val provider = object : lin.serviceLoader.provider.ScoreOperatorProvider {
            override fun getScoreOperators() = listOf<lin.rule.score.ScoreOperator<*, *>>(scoreOp)
        }
        val scoreOpRegistry = lin.rule.score.ScoreOperatorRegistry(listOf(provider))
        val ruleRegistry = lin.rule.registry.RuleRegistry(emptyList())

        val assembler = PipelineAssembler(
            dataSources = mapOf("score_src" to source),
            transforms = mapOf("score_tf" to tf),
            operators = emptyMap(),
            objectMapper = objectMapper
        )

        val compiler = lin.rule.handler.ScoreCompiler(ruleRegistry, assembler, scoreOpRegistry)

        val leafConfigA = lin.rule.tree.OrthogonalRuleLeafConfig(
            nodeId = "r1",
            scoreEffect = lin.rule.score.ScoreEffect.SourceScore(
                sourceId = "score_src",
                transforms = listOf(TransformCall("score_tf")),
                operatorId = "identity_op",
                operatorArgs = mapOf("value" to 0),
                crossCard = true
            )
        )
        val leafConfigB = lin.rule.tree.OrthogonalRuleLeafConfig(
            nodeId = "r2",
            scoreEffect = lin.rule.score.ScoreEffect.SourceScore(
                sourceId = "score_src",
                transforms = listOf(TransformCall("score_tf")),
                operatorId = "identity_op",
                operatorArgs = mapOf("value" to 0),
                crossCard = true
            )
        )

        val scoreLogicA = compiler.compile(leafConfigA)
        val scoreLogicB = compiler.compile(leafConfigB)

        val env = fakeRuleEnv(defaultMockWarInfo)

        val resA = scoreLogicA(FakeRuleContext, env)
        assertEquals(lin.rule.handler.RuleResult.Continue(200.0), resA)
        assertEquals(1, sourceCalls)
        assertEquals(1, transformCalls)

        // 再次评估 crossCard = true 的 Score 管道，100% 缓存命中
        val resB = scoreLogicB(FakeRuleContext, env)
        assertEquals(lin.rule.handler.RuleResult.Continue(200.0), resB)
        assertEquals(1, sourceCalls)
        assertEquals(1, transformCalls)
    }

    @Test
    fun testCrossSideConditionAndScorePipelineCacheReuse() {
        var sourceCalls = 0

        val source = dataSource<Int>("shared_src", "Shared Src") {
            sourceCalls++
            50
        }
        val conditionOp = operator<Int, DummyOpParams>("gte", "GTE") { input, _ -> input > 0 }
        val scoreOp = lin.rule.score.scoreOperator<Int, DummyOpParams>("identity_op", "Identity Op") { input, _ ->
            input.toDouble()
        }

        val provider = object : lin.serviceLoader.provider.ScoreOperatorProvider {
            override fun getScoreOperators() = listOf<lin.rule.score.ScoreOperator<*, *>>(scoreOp)
        }
        val scoreOpRegistry = lin.rule.score.ScoreOperatorRegistry(listOf(provider))
        val ruleRegistry = lin.rule.registry.RuleRegistry(emptyList())

        val assembler = PipelineAssembler(
            dataSources = mapOf("shared_src" to source),
            transforms = emptyMap(),
            operators = mapOf("gte" to conditionOp),
            objectMapper = objectMapper
        )
        val scoreCompiler = lin.rule.handler.ScoreCompiler(ruleRegistry, assembler, scoreOpRegistry)

        val conditionRef = ConditionPayload.PipelineRef(
            refId = "c1",
            sourceId = "shared_src",
            operatorId = "gte",
            operatorArgs = mapOf("value" to 0),
            crossCard = true
        )
        val scoreLeafConfig = lin.rule.tree.OrthogonalRuleLeafConfig(
            nodeId = "s1",
            scoreEffect = lin.rule.score.ScoreEffect.SourceScore(
                sourceId = "shared_src",
                operatorId = "identity_op",
                operatorArgs = mapOf("value" to 0),
                crossCard = true
            )
        )

        val conditionLogic = assembler.assemble(conditionRef)
        val scoreLogic = scoreCompiler.compile(scoreLeafConfig)

        val env = fakeRuleEnv(defaultMockWarInfo)

        // 1. Condition 侧评估，加载 shared_src 并存入 pipe:src:shared_src
        conditionLogic(FakeRuleContext, env)
        assertEquals(1, sourceCalls)

        // 2. Score 侧评估，依赖相同 sourceId，直接跨侧复用 pipe:src:shared_src 缓存！
        scoreLogic(FakeRuleContext, env)
        assertEquals(1, sourceCalls)
    }
}

