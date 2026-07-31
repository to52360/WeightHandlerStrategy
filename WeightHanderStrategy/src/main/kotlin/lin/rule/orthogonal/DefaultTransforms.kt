package lin.rule.orthogonal

import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.enums.CardRaceEnum
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import lin.bean.ComboCard
import lin.bean.groupIds
import lin.bean.purposeTagValues
import lin.domain.MatchActivityKind
import lin.myLog
import lin.rule.context.WarView
import kotlin.system.measureNanoTime


// ==========================================
// 默认转换器 (Transforms)
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
 * 种族过滤器：按指定种族过滤 List<Card>
 */
val RaceFilterTransform = transform<List<Card>, List<Card>, RaceFilterParams>(
    id = "race_filter",
    name = "种族过滤器",
    description = "按指定的种族过滤随从卡牌"
) { input, params ->
    if (params.race == CardRaceEnum.UNKNOWN || params.race == CardRaceEnum.ALL) {
        input
    } else {
        input.filter { it.cardRace == params.race }
    }
}

data class CardTypeFilterParams(
    @lin.rule.parse.RuleField(
        name = "卡牌类型",
        description = "用于过滤的卡牌类型（如 MINION / SPELL / WEAPON）",
        required = true,
        dataSource = "card_types"
    )
    val cardType: CardTypeEnum
)

/**
 * 卡牌类型过滤器：按指定类型过滤 List<Card>
 */
val CardTypeFilterTransform = transform<List<Card>, List<Card>, CardTypeFilterParams>(
    id = "card_type_filter",
    name = "卡牌类型过滤器",
    description = "按卡牌类型（随从/法术/武器等）过滤卡牌列表"
) { input, params ->
    input.filter { it.cardType == params.cardType }
}

data class GroupFilterParams(
    @lin.rule.parse.RuleField(
        name = "分组ID",
        description = "用于过滤的目标分组 ID",
        required = true
    )
    val groupId: String
)

/**
 * 分组过滤器：按指定分组 ID 过滤 List<ComboCard>
 */
val GroupFilterTransform = transform<List<ComboCard>, List<ComboCard>, GroupFilterParams>(
    id = "group_filter",
    name = "分组过滤器",
    description = "按指定的策略分组 ID 过滤 ComboCard 列表"
) { input, params ->
    input.filter { params.groupId in it.groupIds() }
}

data class PurposeFilterParams(
    @lin.rule.parse.RuleField(
        name = "用途标签",
        description = "用于过滤的目标用途标签 ID（如 DRAW_CARD, HEAL, AOE 等）",
        required = true
    )
    val purposeTag: String
)

/**
 * 用途标签过滤器：按指定用途标签过滤 List<ComboCard>
 */
val PurposeFilterTransform = transform<List<ComboCard>, List<ComboCard>, PurposeFilterParams>(
    id = "purpose_filter",
    name = "用途标签过滤器",
    description = "按指定的战术用途标签过滤 ComboCard 列表"
) { input, params ->
    input.filter { params.purposeTag in it.purposeTagValues() }
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
 * 攻击力求和转换器：计算 List<Card> 的累计攻击力总和，输出 Int
 */
val SumAttackTransform = transform<List<Card>, Int>(
    id = "sum_attack",
    name = "攻击力求和器",
    description = "计算卡牌列表中所有卡牌的累计攻击力总和"
) { input, _ ->
    input.sumOf { it.atc }
}

/**
 * 生命值求和转换器：计算 List<Card> 的累计生命值总和，输出 Int
 */
val SumHealthTransform = transform<List<Card>, Int>(
    id = "sum_health",
    name = "生命值求和器",
    description = "计算卡牌列表中所有卡牌的累计生命值/血量总和"
) { input, _ ->
    input.sumOf { it.blood() }
}

/**
 * 评估卡费用提取器：提取当前评估 ComboCard 在手牌中的动态实时费用，输出 Int
 */
val EvaluatingCardCostTransform = transform<ComboCard, Int>(
    id = "evaluating_card_cost",
    name = "动态费用提取器",
    description = "提取当前评估卡牌在手牌中的动态实时消耗费用"
) { input, _ ->
    input.cost()
}

/**
 * 嘲讽随从过滤器：过滤输入卡牌列表中具有嘲讽属性的随从卡牌
 */
val TauntFilterTransform = transform<List<Card>, List<Card>>(
    id = "taunt_filter",
    name = "嘲讽随从过滤器",
    description = "从随从列表中过滤出所有具备嘲讽属性的随从"
) { input, _ ->
    input.filter { it.isTaunt }
}

/**
 * 敌方突破嘲讽溢出伤害转换器：输入 WarView 局势快照，输出 Int 溢出压迫伤害
 */
val ExcessDamageTransform = transform<WarView, Int>(
    id = "excess_damage",
    name = "提取突破嘲讽溢出伤害",
    description = "从战场局势快照 WarView 中提取敌方突破我方嘲讽随从后造成的总溢出压迫伤害"
) { input, _ ->
    input.excessDamage
}

/**
 * 可承受敌方攻击力上限转换器：输入 WarView 局势快照，输出 Int 可承受攻击力上限
 */
val AcceptableAttackTransform = transform<WarView, Int>(
    id = "acceptable_attack",
    name = "提取可承受攻击上限",
    description = "从战场局势快照 WarView 中提取我方当前可承受的敌方随从攻击力上限"
) { input, _ ->
    input.ableAtcSum
}

/**
 * 敌方战场随从提取器：输入 WarView 局势快照，输出 List<Card>
 */
val RivalCardsFromViewTransform = transform<WarView, List<Card>>(
    id = "rival_cards_from_view",
    name = "提取视图敌方随从",
    description = "从战场局势快照 WarView 中提取敌方战场随从列表"
) { input, _ ->
    input.rival.cards
}

/**
 * 我方战场随从提取器：输入 WarView 局势快照，输出 List<Card>
 */
val MeCardsFromViewTransform = transform<WarView, List<Card>>(
    id = "me_cards_from_view",
    name = "提取视图我方随从",
    description = "从战场局势快照 WarView 中提取我方战场随从列表"
) { input, _ ->
    input.me.cards
}

/**
 * ComboCard → Card 桥接转换器：将 ComboCard 列表投影为其底层的 Card 列表
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
 *
 * ⚠️ 临时方案（@defect D-002 部分解决）—— 职责混合，待多数据源重构
 * - 当前混合职责：加权求和（Transform）+ 事件类型匹配判定（Operator），缓存职责已归引用化 Source/评估级 Map
 * - 正确方向：拆为多 DataSource（played_activity_events / graveyard_activity_events 独立输出），
 *   匹配判定交独立 Operator，本 Transform 只对已匹配事件求和
 * - 关联组件：MatchActivityEventsSource（DefaultDataSources.kt）已改为 Map<Kind, List<Card>> 引用返回
 * - 触发重构：再有 Transform 内嵌事件匹配判定的同类案例出现时一并重构
 * - 详见架构上下文 ai-config-validation/DECISIONS.md D-8
 */
val WeightedActivitySumTransform = transform<
        Map<MatchActivityKind, List<Card>>,
        Int,
        WeightedActivitySumParams
        >(
    id = "weighted_activity_sum",
    name = "活动加权求和",
    description = "按事件类型与卡牌ID匹配对局活动事件，累计加权求和得到贡献值"
) { input, params ->
    val played = input[MatchActivityKind.CARD_PLAYED].orEmpty()
    val graveyard = input[MatchActivityKind.CARD_GRAVEYARD].orEmpty()
    var sum = 0
    val elapsedNanos = measureNanoTime {
        for (card in played) {
            if (card.cardId in params.playedCardIds) sum += params.weightPerEvent
        }
        for (card in graveyard) {
            if (card.cardId in params.graveyardCardIds) sum += params.weightPerEvent
        }
    }
    if (elapsedNanos > 1_000_000) { // 超过 1ms (1,000,000 ns) 慢执行才记录
        myLog.warn {
            "[SlowPerf] WeightedActivitySumTransform: ${elapsedNanos / 1000.0} µs (played=${played.size}, graveyard=${graveyard.size}, sum=$sum)"
        }
    }
    sum
}


data class ComboCardCostFilterParams(
    @lin.rule.parse.RuleField(
        name = "目标动态费用",
        description = "用于过滤的目标动态费用数值（如 0 费）",
        required = true
    )
    val cost: Int
)

/**
 * 动态费用过滤器：按动态消耗费用过滤 List<ComboCard>（如筛选 currentCost == 0 的手牌/圣契）
 */
val ComboCardCostFilterTransform = transform<List<ComboCard>, List<ComboCard>, ComboCardCostFilterParams>(
    id = "combo_card_cost_filter",
    name = "动态费用过滤器",
    description = "按当前手牌中的动态实时消耗费用过滤 ComboCard 列表（如筛选 currentCost == 0 的卡牌）"
) { input, params ->
    input.filter { it.cost() == params.cost }
}
