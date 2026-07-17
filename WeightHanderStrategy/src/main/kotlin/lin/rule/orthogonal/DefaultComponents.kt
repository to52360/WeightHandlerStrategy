package lin.rule.orthogonal

import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.enums.CardRaceEnum
import lin.bean.ComboCard
import lin.domain.MatchActivityEvent
import lin.domain.MatchActivityKind
import lin.warExt.my.attack.getGraveyardCards
import lin.warExt.my.base.getHandCards
import lin.warExt.my.base.getPlayCards
import lin.warExt.rival.rivalCardsByPlayArea
import lin.rule.context.RuleContext
import lin.rule.context.RuleEnv
import lin.rule.parse.FieldParser
import lin.rule.parse.FieldSpec
import lin.rule.parse.mapToRuleArgs
import kotlin.reflect.KType
import kotlin.reflect.typeOf

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

/**
 * 对局分组打出计数数据源（D-3）：输出 Map<groupId, count> 整局累计打出次数
 * 无参，通过 RuleEnv.matchState() 读取。配合 pick_group_count Transform 选取指定分组计数。
 * 用 class 非 DSL val —— DSL 闭包内 context receiver 成员不可见，需在 resolve(env: RuleEnv) 中直接调用 env.matchState()。
 */
class MatchGroupPlayedCountsSource : DataSource<Map<String, Int>> {
    override val id: String = "match_group_played_counts"
    override val name: String = "对局分组打出计数"
    override val description: String = "获取本局各分组累计打出次数（整局累计，输出 Map<groupId, count>）"
    override val categories: Set<String> = setOf("对局状态")
    override val outputType: KType = typeOf<Map<String, Int>>()

    context(env: RuleEnv)
    override fun resolve(context: RuleContext): Map<String, Int> {
        return env.matchState().allGroupPlayCounts()
    }
}

/**
 * 对局活动事件数据源（Q-2a）：输出 List<MatchActivityEvent>（打出事件 + 墓地事件）。
 * 无参：打出事件从 MatchState.playedEvents() 读取，墓地事件从 getGraveyardCards() 转换。
 * 首批仅暴露 cardId，不做 groupIds。
 */
class MatchActivityEventsSource : DataSource<List<MatchActivityEvent>> {
    override val id: String = "match_activity_events"
    override val name: String = "对局活动事件"
    override val description: String = "获取本局已打出卡牌的活动事件与墓地卡牌事件，输出事件类型与对应卡牌ID"
    override val categories: Set<String> = setOf("对局状态")
    override val outputType: KType = typeOf<List<MatchActivityEvent>>()

    context(env: RuleEnv)
    override fun resolve(context: RuleContext): List<MatchActivityEvent> {
        return buildList {
            addAll(env.matchState().playedEvents())
            addAll(context.warInfo.getGraveyardCards().map { card ->
                MatchActivityEvent(MatchActivityKind.CARD_GRAVEYARD, card.cardId)
            })
        }
    }
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

data class PickGroupCountParams(
    @lin.rule.parse.RuleField(
        name = "分组ID",
        description = "要查询打出次数的分组 ID",
        required = true
    )
    val groupId: String
)

/**
 * 选取分组计数转换器（D-3）：从 Map<groupId, count> 中选取指定分组的累计打出次数，输出 Int
 */
val PickGroupCountTransform = transform<Map<String, Int>, Int, PickGroupCountParams>(
    id = "pick_group_count",
    name = "选取分组计数",
    description = "从分组打出计数 Map 中选取指定分组的累计打出次数"
) { input, params ->
    input[params.groupId] ?: 0
}

data class WeightedActivitySumParams(
    @lin.rule.parse.RuleField(
        name = "打出卡牌",
        description = "需计入的已打出卡牌 cardId 列表"
    )
    val playedCardIds: List<String> = emptyList(),
    @lin.rule.parse.RuleField(
        name = "墓地卡牌",
        description = "需计入的墓地卡牌 cardId 列表"
    )
    val graveyardCardIds: List<String> = emptyList(),
    @lin.rule.parse.RuleField(
        name = "单事件权重",
        description = "每个匹配事件贡献的权重值"
    )
    val weightPerEvent: Int = 1
)

/**
 * 活动加权求和转换器（Q-2a）：按事件类型和 cardId 匹配，对匹配事件累计权重。
 * 首批仅支持 cardId 精确匹配 + 统一权重，不做 groupId 或差异化权重。
 *
 * 缓存策略：以 [MatchState.playEventVersion] 为失效信号，同一版本号下同一参数组合命中缓存，
 * 避免评估多张圣契引擎卡时重复 sumOf。
 *
 * 用 class 非 DSL val：crossinline DSL lambda 内 context receiver (env) 不可见，
 * 需直接在 resolve 方法中访问 env.pipelineCache() / env.matchState()。
 */
class WeightedActivitySumTransform : Transform<List<MatchActivityEvent>, Int> {
    override val id: String = "weighted_activity_sum"
    override val name: String = "活动加权求和"
    override val description: String = "按事件类型与卡牌ID匹配对局活动事件，累计加权求和得到贡献值"
    override val inputType: KType = typeOf<List<MatchActivityEvent>>()
    override val outputType: KType = typeOf<Int>()
    override val fields: List<FieldSpec> = FieldParser.parse(WeightedActivitySumParams::class)

    context(env: RuleEnv)
    override fun transform(input: List<MatchActivityEvent>, context: RuleContext, args: Map<String, Any>): Int {
        val params = mapToRuleArgs(args, WeightedActivitySumParams::class)
        val version = env.matchState().playEventVersion()
        val cacheKey = "weighted_activity_sum:${params.hashCode()}:$version"
        return env.pipelineCache().getOrCompute(cacheKey) {
            input.sumOf { event ->
                val matched = when (event.kind) {
                    MatchActivityKind.CARD_PLAYED -> event.cardId in params.playedCardIds
                    MatchActivityKind.CARD_GRAVEYARD -> event.cardId in params.graveyardCardIds
                }
                if (matched) params.weightPerEvent else 0
            }
        }
    }
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

/**
 * 等于判定算子
 */
val EqualOp = operator<Int, GteParams>(
    id = "equal",
    name = "等于",
    description = "判定输入值是否等于指定阈值",
    categories = setOf(OperatorCategories.NUMBER, OperatorCategories.COMPARE)
) { input, params ->
    input == params.threshold
}

/**
 * 小于判定算子
 */
val LessThanOp = operator<Int, GteParams>(
    id = "less_than",
    name = "小于",
    description = "判定输入值是否小于指定阈值",
    categories = setOf(OperatorCategories.NUMBER, OperatorCategories.COMPARE)
) { input, params ->
    input < params.threshold
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
