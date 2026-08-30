package lin.rule.orthogonal

import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.enums.CardRaceEnum
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import lin.bean.ComboCard
import lin.bean.groupIds
import lin.bean.purposeTagValues

// ==========================================
// 默认算子 (Operators)
// ==========================================

data class ThresholdParams(
    @lin.rule.parse.RuleField(name = "阈值", description = "比较的阈值", required = true)
    val threshold: Int
)

/**
 * 大于等于判定算子 (gte)
 */
val GreaterThanOrEqualOp = operator<Int, ThresholdParams>(
    id = "gte",
    name = "大于等于",
    description = "判定输入值是否大于等于指定阈值",
    categories = setOf(OperatorCategories.NUMBER, OperatorCategories.COMPARE)
) { input, params ->
    input >= params.threshold
}

/**
 * 大于判定算子 (gt)
 */
val GreaterThanOp = operator<Int, ThresholdParams>(
    id = "gt",
    name = "大于",
    description = "判定输入值是否大于指定阈值",
    categories = setOf(OperatorCategories.NUMBER, OperatorCategories.COMPARE)
) { input, params ->
    input > params.threshold
}

/**
 * 小于等于判定算子 (lte)
 */
val LessThanOrEqualOp = operator<Int, ThresholdParams>(
    id = "lte",
    name = "小于等于",
    description = "判定输入值是否小于等于指定阈值",
    categories = setOf(OperatorCategories.NUMBER, OperatorCategories.COMPARE)
) { input, params ->
    input <= params.threshold
}

/**
 * 小于判定算子 (less_than)
 */
val LessThanOp = operator<Int, ThresholdParams>(
    id = "less_than",
    name = "小于",
    description = "判定输入值是否小于指定阈值",
    categories = setOf(OperatorCategories.NUMBER, OperatorCategories.COMPARE)
) { input, params ->
    input < params.threshold
}

/**
 * 等于判定算子 (equal)
 */
val EqualOp = operator<Int, ThresholdParams>(
    id = "equal",
    name = "等于",
    description = "判定输入值是否等于指定阈值",
    categories = setOf(OperatorCategories.NUMBER, OperatorCategories.COMPARE)
) { input, params ->
    input == params.threshold
}

/**
 * 不等于判定算子 (neq)
 */
val NotEqualOp = operator<Int, ThresholdParams>(
    id = "neq",
    name = "不等于",
    description = "判定输入值是否不等于指定阈值",
    categories = setOf(OperatorCategories.NUMBER, OperatorCategories.COMPARE)
) { input, params ->
    input != params.threshold
}

/**
 * 集合为空判定算子 (is_empty)
 */
val IsEmptyOp = operator<Collection<*>, Unit>(
    id = "is_empty",
    name = "集合为空",
    description = "判定输入的集合/卡牌列表是否为空",
    categories = setOf(OperatorCategories.COLLECTION, OperatorCategories.EXISTENCE)
) { input, _ ->
    input.isEmpty()
}

/**
 * 集合非空判定算子 (is_not_empty)
 */
val IsNotEmptyOp = operator<Collection<*>, Unit>(
    id = "is_not_empty",
    name = "集合非空",
    description = "判定输入的集合/卡牌列表是否非空（包含至少一个元素）",
    categories = setOf(OperatorCategories.COLLECTION, OperatorCategories.EXISTENCE)
) { input, _ ->
    input.isNotEmpty()
}

data class CardTypeMatchParams(
    @lin.rule.parse.RuleField(
        name = "卡牌类型",
        description = "匹配的目标卡牌类型（如 MINION / SPELL / WEAPON）",
        required = true,
        dataSource = "card_types"
    )
    val targetType: CardTypeEnum
)

/**
 * 卡牌类型匹配算子 (is_card_type)
 */
val IsCardTypeOp = operator<Card, CardTypeMatchParams>(
    id = "is_card_type",
    name = "卡牌类型匹配",
    description = "判定单张卡牌的类型是否匹配指定类型（随从/法术/武器等）",
    categories = setOf(OperatorCategories.COMPARE)
) { input, params ->
    input.cardType == params.targetType
}

data class CardRaceMatchParams(
    @lin.rule.parse.RuleField(
        name = "目标种族",
        description = "匹配的随从种族",
        required = true,
        dataSource = "card_races"
    )
    val targetRace: CardRaceEnum
)

/**
 * 单卡种族匹配算子 (is_card_race)
 */
val CardRaceMatchOp = operator<Card, CardRaceMatchParams>(
    id = "is_card_race",
    name = "单卡种族匹配",
    description = "判定单张随从卡牌的种族是否匹配指定种族",
    categories = setOf(OperatorCategories.COMPARE)
) { input, params ->
    input.cardRace == params.targetRace
}

data class BelongsToGroupParams(
    @lin.rule.parse.RuleField(name = "分组ID", description = "匹配的目标分组 ID", required = true)
    val groupId: String
)

/**
 * 卡牌属于指定分组算子 (belongs_to_group)
 */
val CardBelongsToGroupOp = operator<ComboCard, BelongsToGroupParams>(
    id = "belongs_to_group",
    name = "属于指定分组",
    description = "判定 ComboCard 是否属于指定的策略分组 ID",
    categories = setOf(OperatorCategories.COMPARE)
) { input, params ->
    params.groupId in input.groupIds()
}

data class HasPurposeTagParams(
    @lin.rule.parse.RuleField(name = "用途标签", description = "匹配的战术用途标签 ID", required = true)
    val purposeTag: String
)

/**
 * 卡牌包含用途标签算子 (has_purpose_tag)
 */
val CardHasPurposeTagOp = operator<ComboCard, HasPurposeTagParams>(
    id = "has_purpose_tag",
    name = "包含用途标签",
    description = "判定 ComboCard 是否包含指定的战术用途标签",
    categories = setOf(OperatorCategories.COMPARE)
) { input, params ->
    params.purposeTag in input.purposeTagValues()
}

data class ContainsRaceParams(
    @lin.rule.parse.RuleField(name = "目标种族", description = "匹配的种族", required = true, dataSource = "card_races")
    val targetRace: CardRaceEnum
)

/**
 * 集合包含种族算子 (contains_race)
 */
val ContainsRaceOp = operator<Set<CardRaceEnum>, ContainsRaceParams>(
    id = "contains_race",
    name = "包含种族",
    description = "判断输入种族集合中是否包含指定的种族",
    categories = setOf(OperatorCategories.COLLECTION, OperatorCategories.EXISTENCE)
) { input, params ->
    input.contains(params.targetRace)
}

data class HasCardFeatureParams(
    @lin.rule.parse.RuleField(
        name = "卡牌特征",
        description = "匹配的卡牌特征（嘲讽/亡语/圣盾/冲锋/突袭/吸血等）",
        required = true,
        dataSource = "card_features"
    )
    val feature: CardFeature
)

/**
 * 集合包含特征卡牌算子 (has_card_feature)：判定输入的卡牌集合中是否存在具备指定特征的卡牌。
 *
 * 典型用法（T-031）：`hand_combo_cards → has_card_feature(TAUNT)` → 「手牌里有嘲讽牌吗」。
 * 输入 List<ComboCard> 或 List<Card>，输出 Boolean。
 */
val HasCardFeatureOp = operator<Collection<*>, HasCardFeatureParams>(
    id = "has_card_feature",
    name = "包含特征卡牌",
    description = "判定输入的卡牌列表中是否存在具备指定特征（嘲讽/亡语/圣盾等）的卡牌",
    categories = setOf(OperatorCategories.COLLECTION, OperatorCategories.EXISTENCE)
) { input, params ->
    input.any { item ->
        when (item) {
            is ComboCard -> params.feature.matches(item.card)
            is Card -> params.feature.matches(item)
            else -> false
        }
    }
}
