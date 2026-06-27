package lin.tree_config

import lin.rule.handler.EvalOutcome
import lin.rule.tree.EvaluatorInstanceNode
import lin.rule.tree.EvaluatorTreeConfig
import lin.rule.tree.instantiate
import lin.ui.service.createTreeConfigMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EvaluatorTreeIntegrationTest {
    val testJson = """
        {"bindings":[{"type":"GROUP","id":"19fd1490"},{"type":"GROUP","id":"29b7b85a"}],
        "root":{"Leaf":{"payload":{"Rule":{"nodeId":"rule_1778405395362"}}}},
        "leafConfigs":{"rule_1778405395362":{"RULE":{"nodeId":"rule_1778405395362","sourceId":"typed_simple_rule","scoreEffect":{"ConstantScore":{"value":1.0}},"args":{"limit":3}}}}}
        }
    """.trimIndent()

    @Test
    fun testParseAndInstantiate() {
        // 1. 直接解析 JSON 字符串
        val mapper = createTreeConfigMapper()
        val config = mapper.readValue(testJson, EvaluatorTreeConfig::class.java)

        assertNotNull(config, "JSON 应当成功解析为 EvaluatorTreeConfig")
        assertEquals(2, config.bindings.size)
        assertTrue(config.leafConfigs.containsKey("rule_1778405395362"))

        // 2. 实例化为 Tree Instance
        // 【函数式魔法】：完全告别 MockK 和庞大的注册表！
        // 直接传一个符合 (EvaluatorLeafConfig) -> RuleLogic 签名的 Lambda 进去。
        val instance = config.instantiate(
            leafBuilder = {
                // 直接返回一个假的叶子求值闭包
                { EvalOutcome.Matched(1.0) }
            },
            branchConditionBuilder = {
                { true }
            }
        )

        // 3. 验证结果
        assertNotNull(instance)
        assertTrue(instance.root is EvaluatorInstanceNode.RuleNode, "根节点应解析为 RuleNode")

        val rootNode = instance.root as EvaluatorInstanceNode.RuleNode
        assertEquals("rule_1778405395362", rootNode.nodeId)
        assertNotNull(rootNode.leafLogic, "规则逻辑应成功生成（Mock 的闭包）")

        println("成功使用 MockK 解析并实例化出 RuleLogic！")
    }

    @Test
    fun testOrthogonalConditionRoundTrip() {
        // 验证 OrthogonalConditionLeafConfig 的 Jackson 序列化/反序列化往返
        val mapper = createTreeConfigMapper()
        val config = mapper.readValue(testJson, EvaluatorTreeConfig::class.java)

        // 在 leafConfigs 中插入一个正交条件叶子
        val orthoNodeId = "ortho_cond_001"
        val orthoConfig = lin.rule.tree.OrthogonalConditionLeafConfig(
            nodeId = orthoNodeId,
            sourceId = "orthogonal_condition",
            guardCondition = lin.rule.condition.ConditionPayload.PipelineRef(
                sourceId = "hand",
                transforms = emptyList(),
                operatorId = "count_gt",
                operatorArgs = mapOf("target" to 3),
                refId = "ortho_ref_001"
            ),
            scoreEffect = lin.rule.score.ScoreEffect.ConstantScore(2.0, 0.0)
        )
        val updatedConfig = config.copy(
            leafConfigs = config.leafConfigs + (orthoNodeId to orthoConfig)
        )

        // 序列化
        val json = mapper.writeValueAsString(updatedConfig)

        // 验证 JSON 中包含 ORTHOGONAL_CONDITION 标识
        assertTrue(
            json.contains("ORTHOGONAL_CONDITION"),
            "序列化 JSON 应包含 ORTHOGONAL_CONDITION 类型标识"
        )

        // 反序列化
        val deserialized = mapper.readValue(json, EvaluatorTreeConfig::class.java)
        val roundTripConfig = deserialized.leafConfigs[orthoNodeId]
                as lin.rule.tree.OrthogonalConditionLeafConfig
        assertEquals(orthoConfig.guardCondition.operatorId, roundTripConfig.guardCondition.operatorId)
        assertEquals(
            (orthoConfig.scoreEffect as lin.rule.score.ScoreEffect.ConstantScore).value,
            (roundTripConfig.scoreEffect as lin.rule.score.ScoreEffect.ConstantScore).value
        )
        println("正交条件序列化往返测试通过！")
    }
}
