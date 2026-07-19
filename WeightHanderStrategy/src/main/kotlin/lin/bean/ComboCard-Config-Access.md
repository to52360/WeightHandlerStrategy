# CardCombinedConfig 配置边界

## 结构

`CardCombinedConfig` 是启动期预合并的不可变只读配置快照，运行时按 `cardId` 做**一次** HashMap 查找后挂到
`ComboCard.combinedConfig`。

```kotlin
class CardCombinedConfig(
    val weightInfo: CardWeightInfo,
    val groupIds: Set<String> = emptySet(),
    val useIntent: UseIntent = UseIntent(),                    // 启动期预推导，不可变
    val comboEntries: List<CardComboEntry> = emptyList(),      // combo 加权条目
    val comboUseBindings: List<CardComboUseBinding> = emptyList() // 出牌顺序约束
)
```

| 字段                 | 来源                                                          | 消费者                                          |
|--------------------|-------------------------------------------------------------|----------------------------------------------|
| `weightInfo`       | `CardWeightInfoProvide` SPI                                 | 权重评估、规则引擎                                    |
| `groupIds`         | `CardGroupIndexProvider` SPI                                | 分组查询、combo 归并                                |
| `useIntent`        | `UseIntentAssembler`（CardPurpose + GroupUseOverride 合并后预推导） | `UsePlanBuilder`（不再运行时 derive）               |
| `comboEntries`     | `ComboAssembler`（ComboPlanDefinition 归并）                    | `FindBestCombination`                        |
| `comboUseBindings` | `ComboAssembler`（ComboPlanDefinition.relation 解析）           | `ComboUseConstraintBuilder`、`UsePlanOrderer` |

### 为什么删了 `useConfig`

原来的 `useConfig: CardUseConfig` 只在运行时供 `UseIntentDeriver.derive()` 消费。现在改为启动期一次性预推导为
`useIntent: UseIntent`，`UsePlanBuilder` 直接读取 `card.useIntent()`，不再每次推导。`purposeTags` 的运行时访问走独立的
`PurposeTagStore` Koin 单例。

## 组装管线

```
CardConfigBindingTask.execute()
  │
  ├─ SPI 拉取原始数据（baseInfos / groupMap / cardPurposes / groupOverrides / comboDefinitions）
  │
  ├─ UseIntentAssembler(cardPurposes, groupMap, groupOverrides)  // class 实例，Task 结束后回收
  │     └─ assemble(cardId) → UseIntent
  │
  ├─ ComboAssembler(groupMap, comboDefinitions)                  // class 实例，Task 结束后回收
  │     ├─ entries(cardId) → List<CardComboEntry>
  │     └─ bindings(cardId) → List<CardComboUseBinding>
  │
  └─ mapValues → CardCombinedConfig → Koin 注册 "weightInfo"
```

- 各领域 Assembler 是 **`class` 而非 `object`**，Task 结束后随栈帧回收，不残留大对象。
- Assembler 自解析 `cardId`，Task 只需传入领域数据 + 调用一点，不再做预处理。
- 加新配置维度 = 新 Assembler 构造一行 + `mapValues` 蓝图加一个字段。

## 配置访问边界

新增配置时不要继续给 `ComboCard` 本体加镜像字段或镜像方法：

1. **跨领域高频读取** → `ComboCardConfigAccess.kt` 扩展函数（如 `groupIds()`、`hasGroup()`、`hasAnyGroup()`）
2. **跨包消费 / 需要特定可空语义** → 直接作为 `ComboCard` 成员方法（如 `useIntent()` 返回 `UseIntent?`、
   `comboUseBindings()`），避免 `internal` 扩展被成员方法 shadow 的警告
3. **运行时可变状态** → 才放回 `ComboCard` 本体

> 注意：不要对同一读取入口同时保留「成员方法 + 扩展函数」两份实现，Kotlin 中成员方法会 shadow 同名扩展，
> 并触发 "extension is shadowed by a member" 警告。新增读取前先全局搜索确认尚无同名实现。
