package lin.rule.tree

/**
 * 顶层的卡牌分组管理器，维护一套分组方案下的所有 [CardGroupBinding]。
 * [cardGroupManagerId] 对应 DB 主键（UUID String）。
 */
data class CardGroupManagerConfig(
    val cardGroupManagerId: String,
    val name: String,
    val bindings: List<CardGroupBinding>,
    val enabled: Boolean
)

/**
 * 代表一个具体的卡牌分组，归属于 [managerId] 所在的 Manager。
 * 来源文件（sourceFile）属于 UI/持久化关注点，不在业务层暴露。
 *
 * 行为属性（stageOverride / replanAfterUse / orderWeight）由原 GroupUseOverride 合并而来，
 * 表示该分组在出牌决策中的行为覆盖，null 表示"不覆盖"。
 */
data class CardGroupBinding(
    val id: String,
    val managerId: String,
    val name: String,
    val cardIds: List<String>,
    val stageOverride: String? = null,
    val replanAfterUse: Boolean? = null,
    val orderWeight: Double = 0.0
)
