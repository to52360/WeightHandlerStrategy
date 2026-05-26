# §3 数据流详解

## 3.1 初始化阶段

```
ConfigDispatcher 构造
  │
  ├─ handlerMap = handlers.associateBy { it.configType }
  ├─ bindInfoFindMap = bindInfoFind.associateBy { it.targetType }
  │
  └─ init 块：遍历 SPI 加载的所有 BindInfoProvider
       └─ 对每个 BindInfo 调用 processUniformList(findKey, cardConfigs)
```

---

## 3.2 processUniformList 流程

```
processUniformList(ids: List<Any>, cardConfigs: List<CardConfig>)
  │
  ├─ 1. 将 ids 按 KClass 分组
  │     ids.groupBy { it::class } → Map<KClass, List<Any>>
  │
  ├─ 2. 按类型查找对应的 WeightInfoFinder
  │     对每组 key 调用 finder.process(key) → List<CardWeightInfo>
  │     合并为 cardWeightInfos
  │     ★ distinctBy { cardId } 去重（多个 BindingGroupId 可能映射到相同 cardId）
  │
  └─ 3. 调用 dispatch(cardConfigs, cardWeightInfos)
        │
        ├─ 将 cardConfigs 按 ConfigHandler 支持的 configType 分桶
        └─ 对每个非空桶调用 handler.processConfig(group, cardWeightInfos)
```

---

## 3.3 processByType 流程（优化路径）

```
processByType<T : Any>(ids: List<T>, cardConfigs: List<CardConfig>)
  │
  ├─ 1. 取 ids.first()::class → 查 finder（跳过 groupBy）
  │
  ├─ 2. flatMap { finder.process(it) } + distinctBy { it.cardId }
  │
  └─ 3. 判断 cardConfigs 类型
        ├─ 全部同类型 → 直接调用 handler.processConfig（跳过 dispatch 分桶）
        └─ 混合类型 → fallback 到 dispatch(cardConfigs, cardWeightInfos)
```

---

## 3.4 去重机制

多个 `BindingGroupId` 可能映射到相同的 `cardId`（例如不同 CardGroup 包含相同卡牌），导致 `CardWeightInfo` 重复。去重策略：

- **去重位置**：在 `ConfigDispatcher` 层统一执行 `distinctBy { it.cardId }`，而非在 `BindingGroupFinder` 内部
- **原因**：去重是所有 finder 组合的通用关注点，放在 dispatcher 层使所有 finder 受益，且不影响 finder 的单键语义
- **影响范围**：`processUniformList` 和 `processByType` 均在 flatMap 后执行去重
