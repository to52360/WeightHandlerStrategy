package lin.di

import lin.rule.handler.RuleTreeBindingTask
import lin.serviceLoader.provider.StartupTask
import lin.utils.startup.CardConfigBindingTask
import lin.utils.startup.CardPurposeBehaviorStep
import lin.utils.startup.ComboStep
import lin.utils.startup.ConfigBindingStep
import lin.utils.startup.GroupBehaviorStep
import lin.utils.startup.GroupIndexStep
import lin.utils.startup.PredicateGroupStep
import lin.utils.startup.PurposeStep
import lin.utils.startup.WeightInfoStep

/**
 * 启动计划单点（`D-FO-009` / `T-FO-010`）：**过程不进容器，依赖才进容器**（`D-FO-002`）。
 *
 * ## 为什么不做成 Koin 绑定
 * 装配过程对象（7 个 Step + 2 个 Task）**跑完即无用**，却被 `single` 让容器永久持有；且执行顺序
 * 依赖 `getAll<StartupTask>()` 的返回顺序（= Koin **定义顺序**，隐式）⇒ 顺序契约不可推理。
 * 改两个**显式清单**后：顺序在代码里、读者可见；对象 `new` 出来后就是普通局部对象（跑完可回收）；
 * 依赖照旧从容器取（Step 无参构造；Task 自带 `KoinComponent`）。
 *
 * ## ⚠️ 有意收窄（`Q-FO-003` 记重开条件）
 * 不再支持「其他模块注册 `StartupTask`」——全仓现仅本清单两处。**新增启动步骤只能改本文件**；
 * 在 module 里再注册 `StartupTask` 会被**静默忽略**（正是本项目最忌讳的失效形态）。
 * 若将来出现第二个注册方（扩展 jar / configUi / SPI 插件），先落编号再改：改回 `getAll` 或引入显式 SPI 清单。
 *
 * ## 历史陷阱（保留备查：为什么曾经必须是「列表 + 限定符」）
 * Koin 按 `(primaryType, qualifier)` 归档定义，故 ① 7 个 `single<ConfigBindingStep>` 会**互相覆盖**
 * （实测只剩最后注册的 `ComboStep`，其余维度静默不装配且不报错）；② `single<List<X>>` 无条件符仍会被
 * 其它「未限定符的列表定义」覆盖（`List<X>` 擦除后 primaryType 就是 `List`）。⇒ 当时只能「列表 + 限定符」。
 * 现在**清单不经过容器**，两条陷阱随之消失（这也是本方案的附带收益）。
 */
object StartupPlan {

    /** 配置装配步骤；**顺序即执行顺序**（新增一行即接入，勿随意调序——步骤间存在隐式先后依赖）。 */
    fun configBindingSteps(): List<ConfigBindingStep> = listOf(
        WeightInfoStep(),
        GroupIndexStep(),
        GroupBehaviorStep(),
        PredicateGroupStep(),
        PurposeStep(),
        CardPurposeBehaviorStep(),
        ComboStep(),
    )

    /** 启动任务；顺序 = 收窄前现状（配置装配 → 规则树绑定）。 */
    fun startupTasks(): List<StartupTask> = listOf(
        CardConfigBindingTask(configBindingSteps()),
        RuleTreeBindingTask(),
    )
}
