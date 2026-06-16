package lin.rule.condition.orthogonal

import club.xiaojiawei.hsscriptcardsdk.enums.CardRaceEnum
import lin.rule.context.RuleContext
import lin.rule.context.RuleEnv
import lin.rule.parse.FieldSpec
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

// 示例一：己方手牌数量
val HandCardCountSource: DataSource<Int> = dataSource(
    id = "hand_card_count",
    name = "手牌数量",
    description = "当前己方手牌中卡牌的总数量",
    categories = setOf("手牌", "数量")
) { _ ->
    // ARCH-PLACEHOLDER(orthogonal-condition, P-001): 暂时硬编码返回模拟数据，后续接入真实的 env.warView() 提取 | replace-with: env.warView().mine.hand.size
    5
}

// 示例二：战场上随从的种族集合
val BattlefieldRacesSource: DataSource<Set<CardRaceEnum>> = dataSource(
    id = "battlefield_races",
    name = "战场种族集合",
    description = "当前己方战场上所有随从的种族集合",
    categories = setOf("战场", "种族", "集合")
) { _ ->
    // ARCH-PLACEHOLDER(orthogonal-condition, P-002): 暂时返回硬编码的龙族，后续接入真实战场随从解析 | replace-with: env.warView().mine.battlefield.map { it.race }.toSet()
    setOf(CardRaceEnum.DRAGON)
}

