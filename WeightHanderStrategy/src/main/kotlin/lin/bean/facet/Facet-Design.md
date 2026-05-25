# CardWeightInfo Facet 门面模式设计

## 背景

`CardWeightInfo` 起初作为纯数据模型（`data class`），承载卡牌的基础元数据。随着配置类型增加（使用策略、权重规则、combo、意图评估、换牌规则、运行时分组等），字段持续膨胀，逐步演变为上帝类。

此前尝试将部分配置逻辑剥离至 `MyWarManage` / `ComboCard`，但导致职责分散、多模块访问困难等问题。

## 核心矛盾

| 问题       | 症状                                                     |
|----------|--------------------------------------------------------|
| OCP 违反   | 每增加一种新配置类型都要修改 `CardWeightInfo` 主体                     |
| 上帝类      | 字段数过多，难以理解和测试                                          |
| 配置管线耦合   | `ConfigHandler` 直接操作 `CardWeightInfo` 字段，没有访问边界        |
| 之前剥离的副作用 | `runtimeGroupIds` 放入 `ComboCard`，数据生命周期不匹配（瞬时对象持有稳定配置） |

## 方案：Faceted Decomposition（分面拆解）

### 核心思想

**不把 `CardWeightInfo` 拆成多个独立类，而是将可变字段按关注点分组为"分面（Facet）"**。
`CardWeightInfo` 仍然是唯一入口，但内部是分面组合。

```
CardWeightInfo(cardId, powerWeight, groupId, changeWeight)  ← 不可变核心
├─ _cardTypes / _weightRules             ← 不塞 Facet，留在本体
├─ val groups   = CardGroupFacet()       ← 卡牌分组（已落地）
├─ val strategy = StrategyFacet()        ← (未来) 使用策略 + combo + 意图评估
└─ val change   = ChangeFacet()          ← (未来) 换牌规则，独立领域
```

### 新建 Facet 的步骤（模板）

以 `CardGroupFacet` 为例：

1. **在 `lin.bean.facet` 包下新建 Facet 类**
    - 封装该关注点的所有数据 + 方法
    - 保持单一职责

2. **在 `CardWeightInfo` 中声明为 `val` 属性**

   ```kotlin
   val groups = CardGroupFacet()
   ```

3. **在 `MyWarManage.init` 中一次性注入数据**

   ```kotlin
   getCardGroupIndex().forEach { (cardId, groupIds) ->
       infoMap[cardId]?.groups?.addAll(groupIds)
   }
   ```

4. **消费方通过 Facet 访问**

   ```kotlin
   // ComboCard 中的高頻方法可保留為委託
   fun hasGroup(groupId: String) = cardWeightInfo?.groups?.hasGroup(groupId) ?: false
   
   // 低頻直接透過 Facet
   comboCard.cardWeightInfo?.groups?.hasAnyGroup(ids)
   ```

### 设计原则

| 原则        | 说明                                                                                                                |
|-----------|-------------------------------------------------------------------------------------------------------------------|
| **粒度控制**  | 一个 Facet 管一大分类，适当兼职。例如使用策略 + combo + 意图评估全塞 `StrategyFacet`。但不同领域（如换牌）独立成 Facet。小字段（CardType、weightRules）不勉强塞，留本体 |
| **渐进迁移**  | 不要求一次性全拆。每次改到哪个领域就顺势抽一个 Facet，零风险                                                                                 |
| **访问优先级** | 高频方法保留在消费方（如 `ComboCard.hasGroup`）内部委托；低频方法直接用 `info.groups.xxx()`                                                |
| **注入时机**  | `MyWarManage.init`（启动时一次性完成），数据是稳定的配置而非运行时状态                                                                      |
| **单例语义**  | 每个 `CardWeightInfo` 持有独立的 Facet 实例；Facet 实例与应用同生命周期                                                               |

## 当前落地状态

| Facet            | 位置               | 状态    |
|------------------|------------------|-------|
| `CardGroupFacet` | `lin.bean.facet` | ✅ 已落地 |

## 与 ConfigDispatcher 的关系

`ConfigDispatcher` / `ConfigHandler` 的签名 `processConfig(configs, cardWeightInfos)` 不变。
未来，handler 内部从 `info.xxxField` 逐步迁移到 `info.xxxFacet.method()`，

**对比：**

```
改造前（无边界）:        改造后（分面）:
info.useGroupId = 1       info.strategy.useGroupId = 1
info.addCardType(config)  info.strategy.addCardType(config)
```

## 参考

- 首次讨论：`MyWarManage` 中 `runtimeGroupIds` 迁移至 `CardWeightInfo`
- 第一次实现：新建 `CardGroupFacet` + `ComboCardGroupExt` 扩展文件
- 本次调整：移除扩展文件（方法太少），Facet 集中纳入 `lin.bean.facet` 包
