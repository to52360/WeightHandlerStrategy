package lin.ui.combo_plan

import lin.bean.usePlan.ComboRelation

/**
 * 将 ComboRelation 枚举翻译为前台简洁的中文出牌顺序文案
 */
fun ComboRelation.toChineseDesc(): String = when (this) {
    ComboRelation.SCORE_ONLY -> "仅评分"
    ComboRelation.CORE_BEFORE_DEP -> "核心优先"
    ComboRelation.DEP_BEFORE_CORE -> "依赖优先"
}
