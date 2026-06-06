package lin.rule.tree

/**
 * 评估树绑定目标。
 *
 * 统一表达评估树可绑定的目标类型与 id。
 * EvaluatorTreeConfig 持有该绑定列表，命中任一绑定目标的卡牌即挂载此评估树。
 *
 * @param type 绑定目标类型（分组 / 用途标签）
 * @param id 目标唯一标识（分组为 groupId，用途标签为 PurposeTagId.value）
 */
data class EvaluatorTreeBinding(
    val type: EvaluatorTreeBindingType,
    val id: String
)

enum class EvaluatorTreeBindingType {
    /** 卡牌分组绑定 */
    GROUP,

    /** 用途标签绑定 */
    PURPOSE_TAG
}
