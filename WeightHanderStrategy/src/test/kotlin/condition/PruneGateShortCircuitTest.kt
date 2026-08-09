package condition

import lin.rule.handler.EvalOutcome
import lin.rule.handler.evaluateConditionTree
import lin.rule.tree.EvaluatorInstanceNode
import org.junit.Test
import kotlin.test.assertEquals

/**
 * PRUNE 门控短路语义测试（2026-08-09 恢复，prune-semantics/D-001）。
 *
 * 验证核心：PRUNE 与 SCORE+missValue=0 在 AND 组合层面不等价——
 * - SCORE：该叶子未命中只记 missValue，其他子节点照常累加
 * - PRUNE：任一子节点未命中 → 整棵 AND/OR 子树短路（后续子节点不评估）
 */
class PruneGateShortCircuitTest {

    private fun ruleNode(nodeId: String, outcome: EvalOutcome): EvaluatorInstanceNode.RuleNode =
        EvaluatorInstanceNode.RuleNode(nodeId, { outcome })

    private fun ruleNode(nodeId: String, outcome: EvalOutcome, onEval: () -> Unit): EvaluatorInstanceNode.RuleNode =
        EvaluatorInstanceNode.RuleNode(nodeId, { onEval(); outcome })

    private fun and(vararg children: EvaluatorInstanceNode) = EvaluatorInstanceNode.AndNode(children.toList())

    private fun or(vararg children: EvaluatorInstanceNode) = EvaluatorInstanceNode.OrNode(children.toList())

    @Test
    fun `AND 任一子节点 Pruned → 整棵 AND 短路返回 Pruned，后续子节点不评估`() {
        val tracked = mutableListOf<String>()
        val tree = and(
            ruleNode("gate_fail", EvalOutcome.Pruned),
            ruleNode("never_eval", EvalOutcome.Matched(score = 8.0)) { tracked.add("never_eval") },
        )

        val result = evaluateConditionTree(tree, FakeRuleContext, FakeRuleEnv, mutableListOf())

        assertEquals(EvalOutcome.Pruned, result)
        assertEquals(emptyList(), tracked, "Pruned 短路后，后续子节点不应被评估")
    }

    @Test
    fun `AND 全部 SCORE 未命中 → 不是短路，后续子节点照常累加`() {
        // 与 PRUNE 对比：SCORE 叶子未命中只记 missValue，其他子节点照常评估
        val tracked = mutableListOf<String>()
        val tree = and(
            ruleNode("score_miss", EvalOutcome.Skipped(score = 0.0)) { tracked.add("score_miss") },
            ruleNode("hit", EvalOutcome.Matched(score = 8.0)),
        )

        val result = evaluateConditionTree(tree, FakeRuleContext, FakeRuleEnv, mutableListOf())

        assertEquals(EvalOutcome.Matched(score = 8.0), result)
        assertEquals(listOf("score_miss"), tracked, "SCORE 未命中不短路，后续子节点照常评估")
    }

    @Test
    fun `OR 任一子节点 Pruned → 整棵 OR 短路返回 Pruned`() {
        val tree = or(
            ruleNode("branch1_miss", EvalOutcome.Skipped(score = 0.0)),
            ruleNode("gate_fail", EvalOutcome.Pruned),
        )

        val result = evaluateConditionTree(tree, FakeRuleContext, FakeRuleEnv, mutableListOf())

        assertEquals(EvalOutcome.Pruned, result)
    }

    @Test
    fun `OR 首个 Matched 即返回，不评估后续 Pruned 分支`() {
        val tracked = mutableListOf<String>()
        val tree = or(
            ruleNode("hit_first", EvalOutcome.Matched(score = 5.0)),
            ruleNode("prune_never", EvalOutcome.Pruned) { tracked.add("prune_never") },
        )

        val result = evaluateConditionTree(tree, FakeRuleContext, FakeRuleEnv, mutableListOf())

        assertEquals(EvalOutcome.Matched(score = 5.0), result)
        assertEquals(emptyList(), tracked, "OR 首个 Matched 即返回，后续 Pruned 分支不评估")
    }

    @Test
    fun `AND 多条件并列门控 - 一个 Pruned 后即使后续有高分也不贡献`() {
        // 典型场景：减费到位(PRUNE) 且 手牌圣契少(PRUNE) 且 加分
        // 门控 1 未命中 → 整棵 AND 短路，高分叶子不评估
        val tree = and(
            ruleNode("gate_1_fail", EvalOutcome.Pruned),
            ruleNode("gate_2_ok", EvalOutcome.Matched(score = 0.0)),
            ruleNode("score_leaf", EvalOutcome.Matched(score = 10.0)),
        )

        val result = evaluateConditionTree(tree, FakeRuleContext, FakeRuleEnv, mutableListOf())

        assertEquals(EvalOutcome.Pruned, result)
    }

    @Test
    fun `AND 全部门控通过 - 累加打分叶子`() {
        // 门控 1、门控 2 均通过 → 打分叶子累加
        val tree = and(
            ruleNode("gate_1_ok", EvalOutcome.Matched(score = 0.0)),
            ruleNode("gate_2_ok", EvalOutcome.Matched(score = 0.0)),
            ruleNode("score_leaf", EvalOutcome.Matched(score = 10.0)),
        )

        val result = evaluateConditionTree(tree, FakeRuleContext, FakeRuleEnv, mutableListOf())

        assertEquals(EvalOutcome.Matched(score = 10.0), result)
    }
}
