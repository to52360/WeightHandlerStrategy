# 任务追踪 - use-strategy-routing

> 关联：ai-config-validation/TRACKER.md（T-108/T-109 的 use 策略装配后续）
> 决策记录见 [DECISIONS.md](./DECISIONS.md)

## 当前目标

use 策略（使用动作）的装配路由已确认只走新体系 `ConfigBindingStep` 管线（`GroupBehaviorStep` + `CardPurposeBehaviorStep`）；编码侧
`CleanWar`/`ReleaseWar` 的 `afterExtAction` 已全部映射到新体系中（replan → `UseIntent.replanAfterUse` 用途标签驱动，动画等待 →
`Scope.Tag(CLEAN)` 分片）。旧体系的 `RuleInfoRegister` / `ConditionGroup` / `ExtConfig.cardConfigs()` 对策略的链路已切断。

## 整体进度

- **当前任务**: T-1（已实施）；Q-1（已解决，2026-07-15，走用途标签 + 配置分片路径）
- **整体状态**: ✅ 已完成

## 任务列表

### 阶段一：装配路由方向收敛

- [x] **T-1**: use 策略装配路由方向修正（推翻 Koin 全局 map hack，改走新体系 ConfigBindingStep）
    - **背景**：此前为把编码侧 `UseConfig.useStrategyList` 写入策略，给 `UseConfigHandler` 加 `KoinComponent`，在启动装配期
      经 `get<Map<String, CardCombinedConfig>>(named("weightInfo"))` 抓全局 combinedConfig 反向追加 `useStrategies`。这本质是
      给旧体系（ConfigHandler/ConfigDispatcher/RuleInfoRegister）打补丁，而用户明确旧体系不维护。
    - **定论（用户确认）**：`CardCombinedConfig.useStrategies`（`@CardCombinedConfig.kt:21`）是声明 use 策略的**唯一字段**，
      装配只走 `ConfigBindingStep` 管线，由 `GroupBehaviorStep` 从 `group_behavior` 表读 `USE_ACTION` 行为、经
      `UseActionRegistry.resolve` 解析后写入 `builder.useStrategiesByCardId`。运行期注入仍由 `SkillFindStrategy` /
      `RuleTreeBinding` 写 `ComboCard`。
    - **用户明确弃维护的旧体系**：`RuleInfoRegister`（仅负责 old Rule，见 @RuleInfoRegister.kt:22 注释）、
      `ConditionGroup`（`weight_group` 表）、`ExtConfig.cardConfigs() → UseConfig` 旧链路。
    - **已回退改动（2026-07-12）**：
        - `ConfigHandler.kt`：移除 `KoinComponent`、全局 map 抓取、`combinedMap[...].useStrategies.add(...)` 整段；仅保留
          `useGroupId`/`useGroupOrder` 写 `CardWeightInfo`（用户"暂不删"）。`CardWeightInfo` 已无 strategy 字段，故
          `addUseStrategy` 调用整段删除。
        - `CardCombinedConfig.kt`：`useStrategies` 改回不可变 `List<UseStrategy> = emptyList()`，注释注明"唯一来源 =
          GroupBehaviorStep"；`ConfigBindingStep.build` 改 `useStrategies = strategies`（不再 `toMutableList()`）。
    - **验收**：相关文件 `read_lints` 0 错误，无其它处把 `useStrategies` 当可变列表改。
    - **遗留（见 Q-1）**：编码侧 `ExtConfig.cardConfigs()` 产出的 `UseConfig.useStrategyList` 现在无写入点。

### 阶段二：编码侧 use-after-action 失效处理

- [x] **Q-1**: ReleaseWar/CleanWar 等编码侧 use-after-action 失效，如何处置（2026-07-15 已解决，走用途标签 + 配置分片路径）
    - **原问题**：`CleanWar.afterExtAction`（`replanRequested=true` + `extraAwaitMillis=ChangeAnimationTime`）经
      `ExtConfig.cardConfigs() → UseConfig.useStrategyList` 绑定，T-1 后该链路切断，动作失效。
    - **解决方案（拆为两半，各走新体路径）**：

      | CleanWar.afterExtAction | 归属 | 实现 |
      |---|---|---|
      | `replanRequested=true` | **用途标签体系** | `CardPurpose.replanAfterUse`（per cardId，UI 可配）→ `UseDomain.useCard` 接线：`card.useIntent()?.replanAfterUse` 置 `context.replanRequested` |
      | `extraAwaitMillis=ChangeAnimationTime` | **配置分片体系** | `CardPurposeBehaviorStep`：`Scope.Tag(CLEAN) → SliceEntry(useStrategies=[AwaitAnimationStrategy(2500ms)])`，`expandSlices()` 自动按用途标签展开到对应卡 |

    - **配套改动**：
        - `AwaitAnimationStrategy`：`object` → `class(delayMillis)`，支持不同延迟参数
        - `UseActionRegistry`：注册 `"AWAIT_ANIMATION" → AwaitAnimationStrategy(1000)`（默认）
        - 新建 `ConfigSliceScope`(sealed: Group/Tag/Card) + `ConfigSlice` + `SliceEntry` → `CardCombinedConfigBuilder.expandSlices()`
        - `GroupBehaviorStep` 一拆二：OVERRIDE → `groupBehaviors`（直接），USE_ACTION → `slices`(Scope.Group)
        - `CardCombinedConfig` 新增 `purposeTags` 字段（Phase 1 透传）
    - **未覆盖的 orphan 策略**（非 Q-1 主体，另行评估）：`UseAfterLClick`、`DiscoverUseStrategy`、`AwaitAnimationStrategy`（三个 object 目前无消费入口，可延后）
