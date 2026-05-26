# §4 Koin 注册方式

在 `ModulesLoad.configHandler` 模块中：

```kotlin
val configHandler = module {
    singleOf(::UseConfigHandler) bind ConfigHandler::class
    singleOf(::RuleConfigHandler) bind ConfigHandler::class

    single<WeightInfoFinder<String>>(named("finderByTypeString")) {
        val infoMap: Map<String, CardWeightInfo> = get(named("weightInfo"))
        findBy { cardId -> listOfNotNull(infoMap[cardId]) }
    }
    single<WeightInfoFinder<Double>>(named("finderByTypeDouble")) {
        val infoMap = get<Map<String, CardWeightInfo>>(named("weightInfo")).values.groupBy { it.groupId }
        findBy { groupId -> infoMap[groupId] ?: emptyList() }
    }
    single<WeightInfoFinder<BindingGroupId>> {
        BindingGroupFinder(get(named("finderByTypeString")))
    }

    // ConfigDispatcher 自动收集所有 ConfigHandler 和 WeightInfoFinder
    single<ConfigDispatcher> { ConfigDispatcher(getAll(), getAll()) }
}
```

**关键点**：`ConfigDispatcher(getAll(), getAll())` 通过 Koin 的 `getAll()` 自动收集所有已注册的 `ConfigHandler` 和
`WeightInfoFinder`，无需手动传入。

**处理器边界**：`UseConfigHandler` 只注册 `CardAttributeConfig`，`RuleConfigHandler` 只注册 `Rule`。不要再新增多个
`ConfigHandler<BaseConfig>`，否则会让 `BaseConfig` 分桶过宽，甚至在 `associateBy { it.configType }` 时互相覆盖。
