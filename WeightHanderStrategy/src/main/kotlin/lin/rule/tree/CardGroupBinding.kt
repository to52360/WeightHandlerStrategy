package lin.rule.tree

/**
 * 顶层的卡牌分组管理器，维护一套分组方案下的所有 [CardGroupBinding]。
 * [cardGroupMangerId] 对应 DB 主键（UUID String）。
 */
data class CardGroupManagerConfig(
    val cardGroupMangerId: String,
    val name: String,
    val bindings: List<CardGroupBinding>,
    val enabled: Boolean
)

/**
 * 代表一个具体的卡牌分组，归属于 [mangerId] 所在的 Manager。
 * 来源文件（sourceFile）属于 UI/持久化关注点，不在业务层暴露。
 */
data class CardGroupBinding(
    val id: String,
    val mangerId: String,
    val name: String,
    val cardIds: List<String>
)
