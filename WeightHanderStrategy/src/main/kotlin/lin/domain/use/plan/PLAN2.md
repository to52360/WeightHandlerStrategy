# 决策引擎高性能架构演进 (PLAN2)

## 1. 重构背景与痛点

在第一阶段的设计中，我们识别到随着出牌决策逻辑的增加，静态元数据、可变 Facet 以及策略配置在生命周期和数据流动上存在冲突：

- **可变状态污染单例**：`ComboCard` 直接拷贝 `CardWeightInfo` 内的引用字段，在深度回溯搜索时，对属性的修改极易发生隐式共享并污染全局静态单例。
- **Koin 容器与单例污染**：SPI 加载的各种 `Provider` 散落在 `MyWarManage` 与 `ConfigModule` 中，为了解耦被迫在 Koin
  中声明许多短生命周期的单例，污染了全局依赖容器。
- **性能损耗**：获取同一张卡牌的多种配置（权重、分组、使用计划）需要多次进行高频的 HashMap 树形寻址。

---

## 2. “扁平直达 + 100% 不可变构造装配” 架构设计 (已升级)

为了追求极致精简与最高效能，我们在开发中对 Facet 设计进行了二次演进与升级：

### ① 简单属性扁平直达，复杂属性 Facet 隔离

- 对于仅仅包含单一集合的分组领域，我们**彻底抹平了嵌套类 `CardGroupFacet`**，将其直接平铺为 `CardCombinedConfig` 和
  `ComboCard` 的一级只读属性 `groupIds: Set<String>`。
- **寻址性能巅峰**：从原先的 `combinedConfig.groups.groupIds` 缩减为 `combinedConfig.groupIds`
  ，在运行期少了一层指针引用寻址，获得极佳的指令级执行性能！

### ② 强类型复合配置元组 (`CardCombinedConfig`)

复合元组作为单次 HashMap 查找的强类型载体，完美收拢了权重、扁平分组以及复杂策略配置：

```kotlin
class CardCombinedConfig(
    val weightInfo: CardWeightInfo,
    val groupIds: Set<String> = emptySet(),
    val useConfig: CardUseConfig = CardUseConfig()
)
```

- **单次查找**：`infoMap` 统一升级为 `Map<String, CardCombinedConfig>`，决策时只需执行**一次** HashMap 查询。

### ③ 统一提取、构造装配与动态注册 (`CardConfigBindingTask`)

- 专职启动任务 `CardConfigBindingTask` 在启动时一次性提取基础权重与分组配置并执行统一遍历赋值装配。
- 装配出 `finalMap` 后，使用 `loadKoinModules` 动态向 Koin 注册不可变的完全体只读 Map。
- 所有 SPI `Provider` 均在启动任务内局部实例化加载并释放，容器保持极致纯净。

### ④ 消费端适配层极大纯净化

- **`MyWarManage` 极致瘦身**：删去了所有的后置注入和遍历分组逻辑，只作为纯粹的 War 适配层。
- **`ComboCard` 优雅适配**：本体直接声明 `fun groupIds(): Set<String>`，在保持对原有业务代码 100% 兼容的前提下，彻底实现 O(
  1) 扁平直达！

---

## 3. 物理文件变更与精简清单

- **新建**：
    - `lin.bean.CardCombinedConfig.kt`
    - `lin.utils.startup.CardConfigBindingTask.kt`
    - `lin.di.RuleModule.kt`
- **物理删除 (精简)**：
    - `lin.bean.facet.CardGroupFacet.kt` (因分组字段过于简单，彻底抹平，精简掉该类文件)
- **修改**：
    - `lin.bean.CardWeightInfo.kt` (去除 groups)
    - `lin.di.ConfigModule.kt` (取消静态 weightInfo 声明)
    - `lin.di.ModulesLoad.kt` (加载 ruleModule)
    - `lin.domain.MyWarManage.kt` (删除 SPI 逻辑与 getCardGroupIndex)
    - `lin.bean.ComboCard.kt` (成员函数 groupIds() 直达)
    - `lin.domain.ComboDomain.kt` / `lin.domain.WeightHandlerDomain.kt` (适配只读提取映射)
    - `lin.domain.result.FindBestCombination.kt` / `lin.domain.use.plan.ComboUseConstraintBuilder.kt` /
      `lin.domain.use.plan.UsePlanBuilder.kt` (删除冗余扩展引入)
