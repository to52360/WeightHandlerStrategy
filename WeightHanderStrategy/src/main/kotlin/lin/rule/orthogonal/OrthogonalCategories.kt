package lin.rule.orthogonal

/**
 * 正交组件（数据源/运算符）标准分类数据类
 */
data class OrthogonalCategory(
    val id: String,
    val displayName: String,
    val description: String = ""
)

/**
 * 正交组件预定义分类注册表与 Catalog
 */
object OrthogonalCategoryCatalog {
    val HAND = OrthogonalCategory("HAND", "手牌", "涉及手牌数量、具体卡牌特征、费用等")
    val BOARD = OrthogonalCategory("BOARD", "场面/随从", "涉及己方或对方场上随从、攻防状态等")
    val HERO = OrthogonalCategory("HERO", "英雄状态", "涉及血量、护甲、武器、技能状态等")
    val GAME_STATE = OrthogonalCategory("GAME_STATE", "对局全局", "涉及当前回合数、水晶数、对局阶段等")
    val MANA = OrthogonalCategory("MANA", "法力水晶", "涉及剩余水晶、最大水晶、本回合花费等")

    private val allCategories = mutableMapOf(
        HAND.id to HAND,
        BOARD.id to BOARD,
        HERO.id to HERO,
        GAME_STATE.id to GAME_STATE,
        MANA.id to MANA
    )

    fun register(category: OrthogonalCategory) {
        allCategories[category.id] = category
    }

    fun getAll(): List<OrthogonalCategory> = allCategories.values.toList()

    fun findById(id: String): OrthogonalCategory? = allCategories[id]
}
