package lin.rule.tree

import lin.bean.usePlan.ScoreChannel

data class EvaluatorTreeConfig(
    val bindingType: EvaluatorTreeBindingType,
    val bindingIds: List<String>,
    val root: EvaluatorNode,
    val leafConfigs: Map<String, EvaluatorLeafConfig>,
    // 评分通道（树级显式声明，最高优先）。null = 未声明，实例化时由绑定目标的候选策略推导缺省
    // （GROUP→GroupUseOverride.candidatePolicy、PURPOSE_TAG→PurposeTagIntentRule.defaultCandidatePolicy、CARD→GENERAL）。
    val channel: ScoreChannel? = null
)
