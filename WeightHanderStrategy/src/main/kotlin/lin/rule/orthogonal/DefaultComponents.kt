package lin.rule.orthogonal

import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.enums.CardRaceEnum
import lin.bean.ComboCard
import lin.warExt.my.base.getHandCards
import lin.warExt.my.base.getPlayCards
import lin.warExt.rival.rivalCardsByPlayArea

// ==========================================
// 1. 默认数据源
// ==========================================

/**
 * 我方战场随从数据源
 */
val MeBoardCardsSource = dataSource<List<Card>>(
    id = "me_board_cards",
    name = "我方战场随从",
    description = "获取我方战场区域的随从卡牌列表",
    categories = setOf("战场", "卡牌")
) {
    warInfo.getPlayCards()
}

/**
 * 敌方战场随从数据源
 */
val RivalBoardCardsSource = dataSource<List<Card>>(
    id = "rival_board_cards",
    name = "敌方战场随从",
    description = "获取敌方战场区域的随从卡牌列表",
    categories = setOf("战场", "卡牌")
) {
    warInfo.rivalCardsByPlayArea()
}

/**
 * 战场全部卡牌数据源：输出 List<Card>
 */
val BoardCardsSource = dataSource<List<Card>>(
    id = "board_cards",
    name = "全部战场随从",
    description = "获取战场上的全部随从卡牌列表",
    categories = setOf("战场", "卡牌")
) {
    warInfo.getPlayCards() + warInfo.rivalCardsByPlayArea()
}

/**
 * 手牌数据源：输出 List<Card>
 */
val HandCardsSource = dataSource<List<Card>>(
    id = "hand_cards",
    name = "手牌卡牌列表",
    description = "获取当前手牌中的卡牌列表",
    categories = setOf("手牌", "卡牌")
) {
    warInfo.getHandCards()
}

/**
 * 手牌 ComboCard 数据源：输出 List<ComboCard>（含权重分组信息）
 * 直接读取 WarInfo 预计算缓存，零额外开销
 */
val HandComboCardsSource = dataSource<List<ComboCard>>(
    id = "hand_combo_cards",
    name = "手牌ComboCard列表",
    description = "获取手牌的 ComboCard 列表（含权重分组信息，从预计算缓存读取）",
    categories = setOf("手牌", "卡牌")
) {
    warInfo.handComboCards
}

/**
 * 我方战场 ComboCard 数据源：输出 List<ComboCard>（含权重分组信息）
 * 直接读取 WarInfo 预计算缓存，零额外开销
 */
val MeComboCardsSource = dataSource<List<ComboCard>>(
    id = "me_combo_cards",
    name = "我方战场ComboCard",
    description = "获取我方战场的 ComboCard 列表（含权重分组信息，从预计算缓存读取）",
    categories = setOf("战场", "卡牌")
) {
    warInfo.playComboCards
}

// ==========================================
// 2. 默认转换器
// ==========================================

data class RaceFilterParams(
    @lin.rule.parse.RuleField(
        name = "随从种族",
        description = "用于过滤的随从种族",
        required = true,
        dataSource = "card_races"
    )
    val race: CardRaceEnum
)

/**
 * 种族过滤器：按指定种族进行过滤
 */
val RaceFilterTransform = transform<List<Card>, List<Card>, RaceFilterParams>(
    id = "race_filter",
    name = "种族过滤器",
    description = "按指定的种族过滤随从"
) { input, params ->
    if (params.race == CardRaceEnum.UNKNOWN || params.race == CardRaceEnum.ALL) {
        input
    } else {
        input.filter { it.cardRace == params.race }
    }
}

/**
 * 数量投影转换器：接收集合，输出集合中元素个数 Int
 */
val CountProjectionTransform = transform<List<*>, Int>(
    id = "count_projection",
    name = "计数投影器",
    description = "计算集合中元素的数量"
) { input, _ ->
    input.size
}

/**
 * ComboCard → Card 桥接转换器：将 ComboCard 列表投影为其底层的 Card 列表
 * 用于在 ComboCard 级操作（如分组过滤）后切换到 Card 级操作（如种族过滤），避免重写 ComboCard 变体 Transform
 */
val ToCardsTransform = transform<List<ComboCard>, List<Card>>(
    id = "to_cards",
    name = "投影为Card",
    description = "将 ComboCard 列表投影为其底层的 Card 列表，桥接 ComboCard 级与 Card 级管道"
) { input, _ ->
    input.map { it.card }
}

// ==========================================
// 3. 默认算子
// ==========================================

data class GteParams(
    @lin.rule.parse.RuleField(name = "阈值", description = "比较的阈值", required = true)
    val threshold: Int
)

/**
 * 大于等于判定算子
 */
val GreaterThanOrEqualOp = operator<Int, GteParams>(
    id = "gte",
    name = "大于等于",
    description = "判定输入值是否大于等于指定阈值",
    categories = setOf(OperatorCategories.NUMBER, OperatorCategories.COMPARE)
) { input, params ->
    input >= params.threshold
}

data class ContainsRaceParams(
    @lin.rule.parse.RuleField(name = "目标种族", description = "匹配的种族", required = true, dataSource = "card_races")
    val targetRace: CardRaceEnum
)

/**
 * 集合包含种族算子
 */
val ContainsRaceOp = operator<Set<CardRaceEnum>, ContainsRaceParams>(
    id = "contains_race",
    name = "包含种族",
    description = "判断输入种族集合中是否包含指定的种族",
    categories = setOf(OperatorCategories.COLLECTION, OperatorCategories.EXISTENCE)
) { input, params ->
    input.contains(params.targetRace)
}
