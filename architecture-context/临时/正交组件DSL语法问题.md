你这个点其实不是简单的「A 方案不好，B 方案好」。你之前把 `env` 不放进 `RuleContext` 的原因是有道理的：

> 避免 `RuleContext` 变成一个万能上下文袋（Context Pollution），明确区分规则输入和运行环境。

这个考虑没有错。

现在遇到的问题，是 **context receiver 让你想保持分离的设计，在 DSL 表达层变得很别扭**。

我重新看你的场景，我觉得 **不一定要重构成 B 方案**。

---

你的结构大概是：

```
RuleEnv
  |
  |-- 游戏状态
  |-- 卡牌状态
  |-- 战斗信息
  |
RuleContext
  |
  |-- 当前规则执行参数
  |-- callCard
  |-- condition
  |
DataSource
  |
  resolver()
```

这个分层本身合理。

问题只是这里：

```kotlin
context(RuleEnv)
RuleContext.() -> T
```

你同时用了两个 receiver。

执行时：

```
RuleEnv
   +
RuleContext
   +
DataSource lambda
```

对于 Kotlin DSL 来说，会开始复杂。

---

## 其实你可以保留现在设计，只改一点

不要：

```kotlin
context(RuleEnv)
RuleContext.() -> T
```

改成：

```kotlin
RuleContext.(RuleEnv) -> T
```

也就是显式参数。

例如：

```kotlin
typealias Resolver<T> = RuleContext.(RuleEnv) -> T
```

DSL：

```kotlin
val MatchTurnCountSource = dataSource<Int> {
    matchState().turnCount()
}
```

执行：

```kotlin
override fun resolve(
    context: RuleContext,
    env: RuleEnv
): T {
    return context.resolver(env)
}
```

但是这里：

```kotlin
matchState()
```

还是找不到，因为它是 env 的方法。

所以：

```kotlin
val MatchTurnCountSource = dataSource<Int> {
    env -> env.matchState().turnCount()
}
```

会变成：

```kotlin
RuleContext.(RuleEnv) -> T
```

调用：

```kotlin
context.resolver(env)
```

---

这样你的边界保持：

```
RuleContext
    不持有 Env

DataSource
    明确需要 Env
```

代价是 DSL 少一点魔法。

---

## 还有第三种方案，我觉得更适合你的项目

因为你的 DataSource 本质是：

> 从运行时环境读取一个值

其实它不需要 RuleContext。

比如：

```kotlin
interface DataSource<T> {
    fun resolve(env: RuleEnv): T
}
```

直接：

```kotlin
val MatchTurnCountSource = dataSource<Int> {
    matchState().turnCount()
}
```

其中：

```kotlin
inline fun <reified T> dataSource(
    resolver: RuleEnv.() -> T
)
```

执行：

```kotlin
env.resolver()
```

结构：

```
RuleEnv
    |
    DataSource
```

而 RuleContext：

```
RuleContext
    |
    Rule执行过程
```

两者没有耦合。

---

结合你之前的设计（条件、数据源、算子正交化），我反而倾向第三种。

因为：

* DataSource = 提供事实数据
* Operator = 对数据加工
* Condition = 判断

数据源不应该知道 RuleContext。

例如：

```kotlin
CardCountSource
TurnCountSource
ManaSource
```

它们只关心：

```
当前世界状态
```

不是：

```
当前规则正在怎么计算
```

---

所以我的建议排序：

### 如果只是为了少改代码：

保留：

```kotlin
RuleEnv
+
RuleContext
```

但 resolver 改显式参数：

```kotlin
RuleContext.(RuleEnv)->T
```

---

### 如果正在重构数据源体系：

推荐：

```kotlin
DataSource<T> {
    RuleEnv.() -> T
}
```

彻底分离。

---

### 不建议：

```kotlin
RuleContext {
    val env: RuleEnv
}
```

虽然方便：

```kotlin
env.matchState()
```

但你之前担心的 Context 膨胀会回来。

特别是你后面还有：

* CardQuery
* WarInfo
* Config
* EvaluatorState
* Cache

最后容易变：

```kotlin
context.xxx.yyy.zzz
```

成为 Spring ApplicationContext 那种万能对象。

---

所以我不会强推 B。你之前拆开 `env/context` 的判断是正确的，只是 **context receiver 这个 Kotlin 特性不一定适合这里**
。你这个场景更像数据流模型，显式传递反而更稳定。
