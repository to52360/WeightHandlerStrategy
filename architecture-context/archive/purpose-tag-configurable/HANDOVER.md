# 交接报告：用途标签可配置化 + 评估树绑定改造

> 改造日期：2026-06-03
> 编译状态：WeightHandlerStrategy ✅ / configUi ✅

---

## 1. 改造目标

将硬编码的 `PurposeTag` 枚举、`UseIntentDeriver` 时序编排、`EvaluatorTreeConfig` 分组绑定改为可配置体系：

| 改造前                                              | 改造后                                                            |
|--------------------------------------------------|----------------------------------------------------------------|
| `PurposeTag` enum（封闭 6 个值）                       | `PurposeTagId` 值对象（任意字符串）                                      |
| `UseIntentDeriver` 硬编码 `when` 分支                 | `PurposeTagIntentRule` 规则表驱动                                   |
| `EvaluatorTreeConfig.bindGroupIds: List<String>` | `bindings: List<EvaluatorTreeBinding>`（支持 GROUP + PURPOSE_TAG） |
| 引擎层做启用/禁用判断                                      | SPI Provider 边界过滤，引擎无感知                                        |

---

## 2. 新增文件清单

### 引擎层 (WeightHanderStrategy)

| 文件                                     | 类                                                   | 职责                                           |
|----------------------------------------|-----------------------------------------------------|----------------------------------------------|
| `bean/usePlan/PurposeTagId.kt`         | `PurposeTagId`                                      | 用途标签值对象，companion 保留默认常量                     |
| `bean/usePlan/PurposeTagIntentRule.kt` | `PurposeTagIntentRule`                              | 标签→默认意图规则（stage/orderWeight/replan/priority） |
| `rule/tree/EvaluatorTreeBinding.kt`    | `EvaluatorTreeBinding` + `EvaluatorTreeBindingType` | 评估树绑定模型，支持 GROUP / PURPOSE_TAG 两种类型          |
| `rule/tree/PurposeTagBindingId.kt`     | `PurposeTagBindingId`                               | ConfigDispatcher 按用途标签路由的值对象                 |
| `config/find/PurposeTagFinder.kt`      | `PurposeTagFinder`                                  | 按用途标签反向查找卡牌                                  |

### 配置端 (configUi)

| 文件                                            | 类                             | 职责            |
|-----------------------------------------------|-------------------------------|---------------|
| `card_purpose/PurposeTagTreeBindingPolicy.kt` | `PurposeTagTreeBindingPolicy` | 用途标签评估树绑定全局开关 |

---

## 3. 关键修改文件（22个）

### 引擎层

| 文件                         | 变更要点                                                           |
|----------------------------|----------------------------------------------------------------|
| `PurposeTag.kt`            | enum → `@Deprecated typealias PurposeTag = PurposeTagId`       |
| `UseIntent.kt`             | `purposeTags` 类型 → `Set<PurposeTagId>`                         |
| `PurposeTagStore.kt`       | `tags: Map<String, Set<PurposeTagId>>`                         |
| `UseIntentDeriver.kt`      | `object`→`class`，注入 `List<PurposeTagIntentRule>`，按 priority 匹配 |
| `UseIntentAssembler.kt`    | 新增 `private val deriver = UseIntentDeriver()`                  |
| `EvaluatorTreeConfig.kt`   | `bindGroupIds` → `bindings: List<EvaluatorTreeBinding>`        |
| `EvaluatorTreeInstance.kt` | 同步 `bindGroupIds` → `bindings`                                 |
| `RuleTreeBinding.kt`       | 按 `EvaluatorTreeBindingType` 分发给 ConfigDispatcher              |
| `ConfigModule.kt`          | 注册 `PurposeTagFinder`                                          |

### 配置端

| 文件                                            | 变更要点                                                                        |
|-----------------------------------------------|-----------------------------------------------------------------------------|
| `CardPurposeEntities.kt`                      | 解析改用 `PurposeTagId(it.trim())`                                              |
| `CardPurposeRepository.kt`                    | —                                                                           |
| `CardPurposeStore.kt` / `State` / `Workbench` | `PurposeTag` → `PurposeTagId`                                               |
| `TreeConfigEntity.kt`                         | `groupIds` → `bindingsSummary`                                              |
| `TreeConfigRepository.kt`                     | 表列 `group_id` → `bindings_summary` + ALTER TABLE 兼容                         |
| `TreeConfigService.kt`                        | 保存时生成 `bindingsSummary: "TYPE:id,..."`                                      |
| `EvaluatorTreeConfigStrategy.kt`              | save/load 使用 `bindings`                                                     |
| `DefaultTreeActions.kt`                       | `getSelectedGroupIds()` → `getSelectedBindings()`                           |
| `EvaluatorTreeWorkbench.kt`                   | 同步方法签名                                                                      |
| `BindGroupSelector.kt`                        | 重写为分组+用途标签双区选择器                                                             |
| `ConfigListPanel.kt`                          | 适配 `bindingsSummary`                                                        |
| `SqliteTreeConfigProvider.kt`                 | 注入 `CardGroupRepository` + `PurposeTagTreeBindingPolicy`，`filterBindings()` |
| `ConfigUiStrategyProvidersInfo.kt`            | 注册 `PurposeTagTreeBindingPolicy`                                            |

### 测试

| 文件                                | 变更要点                        |
|-----------------------------------|-----------------------------|
| `ConditionCoreTest.kt`            | `bindGroupIds` → `bindings` |
| `EvaluatorTreeIntegrationTest.kt` | JSON 结构同步                   |

---

## 4. 核心架构决策

### 4.1 过滤层级

```
┌──────────────────────────────────────┐
│  configUi (SPI Provider 边界)        │
│  SqliteTreeConfigProvider            │
│  ├─ filterBindings()                 │  ← 唯一过滤点
│  │  ├─ GROUP: CardManagerEntity.enabled
│  │  └─ PURPOSE_TAG: PurposeTagTreeBindingPolicy
│  └─ 返回已过滤的 EvaluatorTreeConfig │
├──────────────────────────────────────┤
│  WeightHandlerStrategy (引擎层)      │
│  RuleTreeBindingTask                 │
│  └─ 只拿到有效绑定，不做启用/禁用判断 │
└──────────────────────────────────────┘
```

### 4.2 分组禁用的关联方式

评估树与分组之间**无外键、无显式关联**，依赖纯字符串 ID 匹配：

```
tree_config.configData (JSON)
  └─ bindings: [{type: GROUP, id: "abc123"}]
                         │  纯字符串匹配
                         ▼
card_group_manager.id: "abc123" / enabled: 0|1
```

禁用判断仅在 `SqliteTreeConfigProvider.filterBindings()` 运行时完成，不落 DB。

### 4.3 UseIntentDeriver 规则匹配

```kotlin
// 按 priority 降序，首个匹配的标签规则决定意图
PurposeTagIntentRule(
    tagId = SAVE_LIFE,
    defaultStage = UsePlanStage.BEFORE_ATTACK,
    orderWeight = 1.0,
    replanAfterUse = false,
    priority = 100
)
```

### 4.4 兼容策略

- `PurposeTag` typealias 保留，旧代码零改动编译
- `tree_config` 表通过 ALTER TABLE 兼容旧列名 `group_id` → `bindings_summary`

---

## 5. 已知缺陷

见 [`KNOWN-DEFECTS.md`](KNOWN-DEFECTS.md)：

| 编号    | 概要                                   | 状态     |
|-------|--------------------------------------|--------|
| D-001 | 评估树 GROUP 绑定依赖分组管理，可观测性未验证           | ⚠️ 可接受 |
| D-002 | DB 无 effective_bindings_summary，不自解释 | ⚠️ 可接受 |

代码中 `@defect D-001` 标记位置：`SqliteTreeConfigProvider.kt:50`

---

## 6. 编译与运行

```bash
# 引擎模块
mvn install -pl WeightHanderStrategy -DskipTests   # BUILD SUCCESS

# 配置模块（依赖引擎 install）
mvn compile -pl configUi                            # BUILD SUCCESS
```

---

## 7. 待后续处理事项

1. **评估 D-001/D-002**：分组状态变更后的热更新路径、`effective_bindings_summary` 存储位置
2. **UI 联动**：分组管理界面是否展示关联的评估树数量
3. **日志级别**：绑定被过滤时的 `debug` 日志是否需要在特定场景升为 `warn`
4. **PurposeTagTreeBindingPolicy 持久化**：当前为内存对象，默认全开，后续需接入 SQLite + UI 配置
