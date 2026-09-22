package lin.di

import lin.bean.CardCombinedConfig
import lin.config.find.def.WeightInfoFinder
import lin.config.handler.ConfigHandler
import lin.config.handler.RuleConfigHandler
import lin.config.handler.UseConfigHandler
import lin.rule.tree.BindingGroupId
import lin.rule.tree.CardBindingId
import lin.rule.tree.PurposeTagBindingId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * 守住 `ConfigDispatcher` 的两条装配链：**五个 finder 一个都不能少** + handlers 能被 `getAll` 收到。
 *
 * ## 为什么需要本测试（2026-09-22 宿主实测踩到）
 *
 * 三个「路由键 finder」（`BindingGroupId` / `PurposeTagBindingId` / `CardBindingId`）的 Koin 定义类型
 * 都是 `WeightInfoFinder<*>`——**泛型被擦除** ⇒ 无名注册时它们共用同一个 Koin key、互相覆盖，
 * `getAll<WeightInfoFinder<*>>()` 只返回最后注册的那个（实测只剩 `CardBindingFinder`）。
 * 后果不是报错，而是 `RuleTreeBindingTask` 派发 GROUP / PURPOSE_TAG 树根时查不到 finder，
 * 只留一行 `WARN 不支持类型` —— **这两类绑定的评估树整体静默失效（树分恒为 0）**。
 *
 * 修复方式：三个 finder 各带限定符 + `ConfigDispatcher` 显式成表（见 `ConfigModule`）。
 * 本测试即为该修复的回归护栏。
 */
class KoinFinderWiringTest {

    /** 与 `ConfigModule` 同构的最小容器（只补 `weightInfo`，其余由 configModule 自带）。 */
    private fun appWithConfigModule() = koinApplication {
        modules(
            module { single<Map<String, CardCombinedConfig>>(named("weightInfo")) { emptyMap() } },
            configModule,
        )
    }

    @Test
    fun `configModule 的 finder 表覆盖三个路由键`() {
        val koin = appWithConfigModule().koin
        val targets = listOf(
            "finderByTypeString",
            "finderByTypeDouble",
            FINDER_BY_BINDING_GROUP,
            FINDER_BY_PURPOSE_TAG,
            FINDER_BY_CARD,
        ).map { qualifier -> koin.get<WeightInfoFinder<*>>(named(qualifier)).targetType.qualifiedName }
        println(">>> finder targets=$targets")

        assertTrue(
            "BindingGroupId finder 缺失 ⇒ GROUP 绑定树整体失效。实际: $targets",
            targets.contains(BindingGroupId::class.qualifiedName)
        )
        assertTrue(
            "PurposeTagBindingId finder 缺失 ⇒ PURPOSE_TAG 绑定树整体失效。实际: $targets",
            targets.contains(PurposeTagBindingId::class.qualifiedName)
        )
        assertTrue(
            "CardBindingId finder 缺失 ⇒ CARD 绑定树整体失效。实际: $targets",
            targets.contains(CardBindingId::class.qualifiedName)
        )
    }

    /**
     * 另一半：`handlers = getAll<ConfigHandler<*>>()` 必须收到两个处理器——
     * 若为 0，每个树根都会落到 `No handler for:` 分支，树同样静默失效。
     * （这两个经 `bind ConfigHandler::class` 注册，primaryType 各不相同 ⇒ 无覆盖问题。）
     */
    @Test
    fun `getAll 必须能收到经 bind 注册的 ConfigHandler`() {
        val app = koinApplication {
            modules(
                module {
                    singleOf(::UseConfigHandler) bind ConfigHandler::class
                    singleOf(::RuleConfigHandler) bind ConfigHandler::class
                }
            )
        }
        val handlers = app.koin.getAll<ConfigHandler<*>>()
        println(">>> handler configTypes=${handlers.map { it.configType.qualifiedName }}")
        assertEquals("经 bind 注册的 ConfigHandler 必须能被 getAll 收到", 2, handlers.size)
    }
}
