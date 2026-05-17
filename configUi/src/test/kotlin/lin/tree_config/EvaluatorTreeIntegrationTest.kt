package lin.tree_config

import lin.rule.handler.RuleResult
import lin.rule.tree.EvaluatorInstanceNode
import lin.rule.tree.EvaluatorTreeConfig
import lin.rule.tree.instantiate
import lin.tree_config.service.createTreeConfigMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EvaluatorTreeIntegrationTest {
    val testJson = """
        {"bindGroupIds":["19fd1490","29b7b85a"],
        "root":{"Leaf":{"payload":{"Rule":{"nodeId":"rule_1778405395362"}}}},
        "ruleConfigs":{"rule_1778405395362":{"nodeId":"rule_1778405395362","ruleId":"typed_simple_rule","weight":1.0,"mismatchedWeight":2.0,"args":{"limit":3}}}}
        }
    """.trimIndent()

    @Test
    fun testParseAndInstantiate() {
        // 1. 直接解析 JSON 字符串
        val mapper = createTreeConfigMapper()
        val config = mapper.readValue(testJson, EvaluatorTreeConfig::class.java)

        assertNotNull(config, "JSON 应当成功解析为 EvaluatorTreeConfig")
        assertEquals(2, config.bindGroupIds.size)
        assertTrue(config.ruleConfigs.containsKey("rule_1778405395362"))

        // 2. 实例化为 Tree Instance
        // 【函数式魔法】：完全告别 MockK 和庞大的注册表！
        // 直接传一个符合 (RuleConfig) -> RuleLogic 签名的 Lambda 进去。
        val instance = config.instantiate { ruleConfig ->
            // 直接返回一个假的规则执行闭包
            { RuleResult.Continue(1.0) }
        }

        // 3. 验证结果
        assertNotNull(instance)
        assertTrue(instance.root is EvaluatorInstanceNode.RuleNode, "根节点应解析为 RuleNode")

        val rootNode = instance.root as EvaluatorInstanceNode.RuleNode
        assertEquals("rule_1778405395362", rootNode.nodeId)
        assertNotNull(rootNode.ruleLogic, "规则逻辑应成功生成（Mock 的闭包）")

        println("成功使用 MockK 解析并实例化出 RuleLogic！")
    }
}
