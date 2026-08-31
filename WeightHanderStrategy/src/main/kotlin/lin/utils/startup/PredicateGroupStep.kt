package lin.utils.startup

import lin.domain.use.plan.GroupMembershipRuntime
import lin.rule.handler.GuardCompiler
import lin.serviceLoader.provider.PredicateGroupProvider
import org.koin.core.component.KoinComponent

/**
 * 装配「谓词组」（条件定义成员的分组）到运行时求值器（T-002）。
 *
 * 谓词组**不产生** [lin.bean.CardCombinedConfig] 内容——成员是运行时逐卡判定的，
 * 无法在启动期展开成 cardId。本 Step 只做一件事：把谓词组定义与条件编译器交给
 * [GroupMembershipRuntime]，由 `ComboCard.groupIds()` 在运行时合并静态组与谓词组。
 *
 * GuardCompiler 取不到时不抛异常：装配期 Koin 可能尚未就绪（单测/部分启动路径），
 * 此时谓词组全部降级为「不匹配」（见 GroupMembershipRuntime 的降级策略）。
 */
class PredicateGroupStep : ConfigBindingStep, KoinComponent {
    override fun contribute(builder: CardCombinedConfigBuilder) {
        val defs = getKoin().getAll<PredicateGroupProvider>().flatMap { it.provide() }
        val compiler = try {
            getKoin().getOrNull<GuardCompiler>()
        } catch (e: Throwable) {
            null
        }
        GroupMembershipRuntime.configure(defs, compiler)
    }
}
