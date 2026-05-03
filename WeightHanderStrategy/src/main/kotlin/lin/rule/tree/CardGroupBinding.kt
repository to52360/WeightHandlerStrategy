package lin.rule.tree

/** 顶层的卡牌分组管理器 维护所有的分组 */
data class CardGroupManagerConfig(
    val cardGroupMangerId: Int,
    val groupIds: List<String>,
    val enabled: Boolean
)

/** 代表一个具体的卡牌分组，对应 EvaluatorTreeConfig.bindByGroupId */
data class CardGroupBinding(val groupId: String, val name: String, val cardIds: List<String>)
