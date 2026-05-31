# ComboCard 配置访问边界

`ComboCard` 是运行时状态容器，负责保存当前对局里会变化的瞬时状态，例如目标、临时权重、使用顺序权重等。

`CardCombinedConfig` 是启动期预合并出来的只读配置快照。运行期只在 `MyWarManage.parseComboCard` 里按 `cardId` 做一次查找，然后挂到
`ComboCard.combinedConfig` 上。

因此新增配置时不要继续给 `ComboCard` 本体加镜像字段或镜像方法。优先按下面规则放置访问入口：

1. 跨领域高频使用的简单读取，放在 `ComboCardConfigAccess.kt` 作为 `ComboCard` 扩展函数。
2. 只被某个领域使用的配置，放在对应领域目录作为内部扩展函数，例如 `use.plan/ComboCardUsePlanAccess.kt` 自己读取 use 编排配置。
3. 运行时会变化的状态，才允许放回 `ComboCard` 本体。

这样保留了热路径的一次配置查找，同时避免每新增一个配置维度都修改运行时容器。

# CardCombinedConfig 配置边界问题

## 强类型复合配置元组 (`CardCombinedConfig`)

复合元组作为单次 HashMap 查找的强类型载体，完美收拢了权重、扁平分组以及复杂策略配置：

```kotlin
class CardCombinedConfig(
    val weightInfo: CardWeightInfo,
    val groupIds: Set<String> = emptySet(),
    val useConfig: CardUseConfig = CardUseConfig()
)
```

- **单次查找**：`infoMap` 统一升级为 `Map<String, CardCombinedConfig>`，决策时只需执行**一次** HashMap 查询。

### 统一提取、构造装配与动态注册 (`CardConfigBindingTask`)

- 专职启动任务 `CardConfigBindingTask` 在启动时一次性提取基础权重与分组配置并执行统一遍历赋值装配。
- 装配出 `finalMap` 后，使用 `loadKoinModules` 动态向 Koin 注册不可变的完全体只读 Map。
- 所有 SPI `Provider` 均在启动任务内局部实例化加载并释放，容器保持极致纯净。
