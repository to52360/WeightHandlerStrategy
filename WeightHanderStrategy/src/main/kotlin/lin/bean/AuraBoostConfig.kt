package lin.bean

/**
 * Push 广播评分配置（aura-boost D-001/D-002）。
 *
 * 光环/全局条件满足后，把加分推给所有受益卡（targetConditionId 判定），
 * 消除 pull 模型"每张受益卡一棵树写光环条件"的 N×M 配置膨胀。
 *
 * additive 独立通道（D-004）：命中分与评估树分相加；
 * 边界约定"光环加分只走 AuraBoost，评估树不写光环条件"，防双通道重复打分。
 *
 * @param conditionId       触发条件树 id（如"莱妮莎在场"）。惯例只用全局源
 *                          （war_view / me_combo_cards / hand_cards），命中与否与具体卡无关，
 *                          pipelineCache 整局兜底。
 * @param targetConditionId 受益卡过滤条件树 id（可引 evaluating_card，per-card 判定"这张卡是受益者吗"）。
 * @param score             命中加分。
 */
data class AuraBoostConfig(
    val id: String,
    val name: String? = null,
    val conditionId: String,
    val targetConditionId: String,
    val score: Double
)
