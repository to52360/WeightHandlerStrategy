你现在迷糊是正常的，因为从代码看，`RuleContext` 和 `RuleEnv` **确实已经有部分重合**。

我觉得关键不是「哪个应该放什么」，而是你需要重新定义两个对象的 **时间维度和职责边界**。

你之前的设计其实隐含了两个概念：

---

## RuleContext：一次规则计算的局部上下文

你注释里：

> 封装了出牌的基本信息，并为未来的决策推演层预留入口

这个方向是对的。

它应该描述：

> **我正在评估哪张牌，针对哪个决策点**

例如：

```kotlin
data class RuleContext(
    val callCard: ComboCard,
    val warInfo: WarInfo
)
```

它的生命周期：

```
一次规则评估
    |
    创建 RuleContext
    |
    执行几十个 Rule
    |
    丢弃
```

特点：

* 强绑定当前决策
* 变化频繁
* 不应该缓存

比如：

```kotlin
"我要评估火球术是否值得打"
```

那么：

```kotlin
callCard = 火球术
```

---

## RuleEnv：规则运行的外部世界

你的：

```kotlin
class WarInfoEnv(private val warManage: MyWarManage)
```

其实更像：

> 当前战局的依赖环境

生命周期：

```
一局游戏
 |
 RuleEnv
 |
 多轮计算
 |
 多次 RuleContext
```

特点：

* 稳定
* 提供服务
* 可以缓存

例如：

```kotlin
env.matchState()
env.pipelineCache()
env.warView()
```

---

所以理论上：

```
                 RuleEnv
                    |
        ------------------------
        |                      |
   RuleContext1          RuleContext2
   火球术                  寒冰箭
```

---

但是你的问题来自这里：

```kotlin
RuleContext(
    val warInfo : WarInfo
)
```

这个字段破坏了边界。

因为：

`WarInfo` 是什么？

看名字：

> War Information

它更像环境。

所以现在变成：

```
RuleContext
 |
 + callCard
 |
 + warInfo
       |
       + matchState
       + warView
       + cache
```

于是：

RuleContext 持有环境。

然后又有：

```
RuleEnv
 |
 + matchState
 + warView
 + cache
```

重复了。

---

## 我认为应该调整的是这里

不要：

```kotlin
data class RuleContext(
    val callCard: ComboCard,
    val warInfo: WarInfo
)
```

改：

```kotlin
data class RuleContext(
    val callCard: ComboCard
)
```

然后：

```kotlin
interface RuleEnv {
    fun warInfo(): WarInfo
    fun matchState(): MatchState
    fun pipelineCache(): PipelineCache
}
```

关系：

```
RuleContext
    |
    | 当前动作
    |
    v

RuleEnv
    |
    | 世界状态
    |
    v

Game
```

---

执行：

```kotlin
rule(
    RuleContext(card),
    env
)
```

规则：

```kotlin
{
    val hand = env.warInfo().handCards()

    val cost = callCard.cost
}
```

---

## 那为什么以前你可能把 WarInfo 放 Context？

因为它确实有一个中间属性：

`WarInfo` 既像：

* 当前战场信息（Env）
* 又像规则计算输入（Context）

这就是重合来源。

但是从你的新架构看：

你已经引入：

* DataSource
* Operator
* Evaluator
* PipelineCache

那么 `WarInfo` 越来越像基础设施。

应该归 Env。

---

我会建议最终结构：

```
Game State
    |
    v
RuleEnv
    - warInfo()
    - matchState()
    - cache()


Decision Target
    |
    v
RuleContext
    - callCard
    - maybe targetCard
    - maybe action


Rule
    |
    + RuleContext
    + RuleEnv
```

---

这样你的 DataSource 也自然：

```kotlin
interface DataSource<T> {
    fun resolve(env: RuleEnv): T
}
```

而不是：

```kotlin
RuleContext + RuleEnv
```

---

不过这里有一个需要确认的问题：

你的 `WarInfo` 现在是不是 **纯快照**？

如果：

```kotlin
WarInfo(
    handCards,
    canUseCards,
    playCards,
    cost,
    status
)
```

是一次出牌计算过程中的不可变视图，那么它其实应该直接成为 `RuleContext`。

如果：

```kotlin
WarInfo
-> matchState()
-> cache()
-> reload()
```

带动态能力，那么它应该回归 `RuleEnv`。

从你之前贴过的接口：

```kotlin
interface WarInfo {
    fun cleanPlayByRoundOnce()
    fun roundExecuteOnce()
    fun reloadPlayComboCards()
    fun registerLifecycle()
}
```

我倾向它已经不是纯 Info，而是一个运行时对象。

所以现在的分层确实需要调整。你这次发现的不是 Kotlin DSL 问题，而是架构边界暴露出来了。
