package lin.rule.condition.orthogonal

import club.xiaojiawei.hsscriptcardsdk.enums.CardRaceEnum
import lin.bean.cardExt.base.isMinion
import lin.rule.context.RuleContext
import lin.rule.context.RuleEnv
import lin.rule.context.toWarView
import lin.rule.parse.FieldSpec
import lin.warExt.my.base.getHandCards
import kotlin.reflect.KClass

/**
 * 数据源：负责从执行环境中提取原始数据，不含任何比较逻辑。
 * 支持在 fields 中声明其所需的参数定义。
 */
interface DataSource<out T : Any> {
    val id: String
    val name: String
    val description: String
    val categories: Set<String>       // 用于 UI 过滤推荐，如 {"手牌", "数量"}
    val outputType: KClass<out T>     // 用于校验与 Operator 的类型兼容性
    val fields: List<FieldSpec> get() = emptyList()

    context(env: RuleEnv)
    fun resolve(context: RuleContext, args: Map<String, Any> = emptyMap()): T
}

/**
 * 动态数据源的 DSL 构建器
 */
inline fun <reified T : Any> dataSource(
    id: String,
    name: String,
    description: String = "",
    categories: Set<String> = emptySet(),
    fields: List<FieldSpec> = emptyList(),
    crossinline resolver: context(RuleEnv) RuleContext.(Map<String, Any>) -> T
): DataSource<T> = object : DataSource<T> {
    override val id = id
    override val name = name
    override val description = description
    override val categories = categories
    override val outputType = T::class
    override val fields = fields

    context(env: RuleEnv)
    override fun resolve(context: RuleContext, args: Map<String, Any>): T {
        return context.resolver(args)
    }
}

/**
 * 带有强类型参数的动态数据源 DSL 构建器
 */
@JvmName("dataSourceParameterized")
inline fun <reified T : Any, reified P : Any> dataSource(
    id: String,
    name: String,
    description: String = "",
    categories: Set<String> = emptySet(),
    paramSpecs: List<FieldSpec>? = null,
    crossinline resolver: context(RuleEnv) RuleContext.(P) -> T
): DataSource<T> {
    val resolvedSpecs = paramSpecs ?: lin.rule.parse.FieldParser.parse(P::class)
    return object : DataSource<T> {
        override val id = id
        override val name = name
        override val description = description
        override val categories = categories
        override val outputType = T::class
        override val fields = resolvedSpecs

        context(env: RuleEnv)
        override fun resolve(context: RuleContext, args: Map<String, Any>): T {
            val parameter = lin.rule.parse.mapToRuleArgs(args, P::class)
            return context.resolver(parameter)
        }
    }
}

// 示例一：己方手牌数量
val HandCardCountSource: DataSource<Int> = dataSource(
    id = "hand_card_count",
    name = "手牌数量",
    description = "当前己方手牌中卡牌的总数量",
    categories = setOf("手牌", "数量")
) { _ ->
    warInfo.getHandCards().size
}

// 示例二：战场上随从的种族集合
val BattlefieldRacesSource: DataSource<Set<CardRaceEnum>> = dataSource(
    id = "battlefield_races",
    name = "战场种族集合",
    description = "当前己方战场上所有随从的种族集合",
    categories = setOf("战场", "种族", "集合")
) { _ ->
    warInfo.toWarView().me.cards.asSequence()
        .filter { it.isMinion() }
        .map { it.cardRace }
        .filter { it != CardRaceEnum.UNKNOWN }
        .toSet()
}

// 战场随从计数参数与数据源
data class MinionsCountParams(
    @lin.rule.parse.RuleField(
        name = "随从方",
        description = "计算我方、敌方或双方的战场随从",
        required = true,
        dataSource = "side_types"
    )
    val side: String = "ME",
    @lin.rule.parse.RuleField(
        name = "随从种族",
        description = "用于过滤的随从种族，留空表示所有种族",
        required = false,
        dataSource = "card_races"
    )
    val race: CardRaceEnum? = null,
    @lin.rule.parse.RuleField(name = "是否嘲讽", description = "是否仅过滤嘲讽随从", required = false)
    val isTaunt: Boolean? = null
)

val MinionsCountSource = dataSource<Int, MinionsCountParams>(
    id = "minions_count",
    name = "战场随从数量",
    description = "战场上我方、敌方或双方的随从总数量，支持按种族或嘲讽过滤",
    categories = setOf("战场", "数量")
) { params ->
    val view = warInfo.toWarView()
    val candidates = when (params.side.uppercase()) {
        "ME" -> view.me.cards
        "RIVAL" -> view.rival.cards
        else -> view.me.cards + view.rival.cards
    }.filter { it.isMinion() }

    var filtered = candidates
    params.race?.let { r ->
        if (r != CardRaceEnum.UNKNOWN && r != CardRaceEnum.ALL) {
            filtered = filtered.filter { it.cardRace == r }
        }
    }
    params.isTaunt?.let { t ->
        filtered = filtered.filter { it.isTaunt == t }
    }
    filtered.size
}
