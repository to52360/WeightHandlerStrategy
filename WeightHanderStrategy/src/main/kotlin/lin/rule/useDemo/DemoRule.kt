package lin.rule.useDemo

import lin.rule.build.RuleBuilder
import lin.rule.build.RuleLogic
import lin.rule.build.RuleRegistration
import lin.rule.handler.RuleResult
import lin.rule.parse.RuleField
import lin.serviceLoader.provider.RuleRegistrationProvider


/**
 * =========================================================================
 * 方案二：全项目复用的标量数据类（强烈推荐）
 *
 * 场景：为了不写烦人的类型转换（params["x"] as Int），同时享受编译器支持。
 * 核心：在项目公共包下一次性定义好 `IntValueArg`, `StringValueArg` 供全项目复用。
 * =========================================================================
 */
// 这个数据类可以放到全局 utils 里去，全项目规则复用
data class IntValueArg(
    @RuleField("阈值设定", "规则的通用整形参数") val limit: Int
)
data class IntsValueArg(
    @RuleField("阈值设定", "规则的通用整形参数") val limits: List<Int>
)

val typedSimpleRule = RuleBuilder(IntValueArg::class)
    .id("typed_simple_rule")
    .metadata(name = "费用阈值加分规则", desc = "当前出牌费用不超过阈值时给 1.0 分，否则 0.0")
    .factory { leafConfig, params ->
        // 🌟 优势：直接拿到强类型的值，编译期间绝对安全，没有 Map 的解构开销
        val limit = params.limit

        val logic: RuleLogic = {
            val cost = callCard.card.cost
            RuleResult.Continue(score = if (cost <= limit) 1.0 else 0.0)
        }
        logic
    }
    .build()
val listSimpleRule = RuleBuilder(IntsValueArg::class)
    .id("list_simple_rule")
    .metadata(name = "费用命中加分规则", desc = "当前出牌费用命中给定列表时给 1.0 分，否则 0.0")
    .factory { leafConfig, params ->
        // 🌟 优势：直接拿到强类型的值，编译期间绝对安全，没有 Map 的解构开销
        val limits = params.limits

        val logic: RuleLogic = {
            val cost = callCard.card.cost
            RuleResult.Continue(score = if (cost in limits) 1.0 else 0.0)
        }
        logic
    }
    .build()


/**
 * =========================================================================
 * 前端 UI 数据是如何获取这些结果的演示（伪代码）
 * =========================================================================
 */
fun printUiDataDemo() {
    // 我们假设这俩都被注册进了 RuleRegistry
    // 对于 dynamicSimpleRule：
    // 对于 dynamicSimpleRule：
    //   它的 fields 会被 extraField 闭包解析为： [FieldSpec(propertyName=limitCount, type=IntType)]

    // 对于 typedSimpleRule：
    //   它的 fields 会被解析器根据注解扫描为：[FieldSpec(propertyName=limit, type=IntType)]

    // 前端画界面的同学，拿到的始终是同样的 FieldSpec 数组结构，他们完全不关心你是用 Map 还是实体类写的。
}

/**
 * =========================================================================
 * 服务注册（供 SPI / ServiceLoader / Koin 自动发现加载）
 * =========================================================================
 */
// 如果你项目用了 Google AutoService，可以在类上方加上 @AutoService(RuleRegistrationProvider::class)
class DemoRuleProvider : RuleRegistrationProvider {
    override fun getRuleRegistrations(): Collection<RuleRegistration<*>> {
        return listOf(
            typedSimpleRule, listSimpleRule
        )
    }
}
