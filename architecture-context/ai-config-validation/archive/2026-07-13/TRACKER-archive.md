# TRACKER 归档详情 - ai-config-validation (2026-07-13)

> 本文件存放从主 TRACKER.md 压缩归档的已完成任务详细记录。主文件仅保留单行摘要 + `(归档批次: 2026-07-13)`。

## 阶段一：V1 收尾（MCP 工具闭环与实战验证）

- [x] **T-101**: 暴露 get_draft_status MCP 工具（`DraftTreeService.getDraftStatus()` +
  `AiDraftTreeToolProvider.typedTool<GetDraftStatusRequest>`，支撑断点续传）
- [x] **T-102**: 端到端实战验证（`McpToolDriverTest` 直接调 `McpToolDriverTest` 跑通 19 工具全链路 SOP，见主文件「参考：实战验证记录」）
- [x] **T-103**: 根据实战反馈优化工具描述（`list_orthogonal_components` 补 in/out 类型签名、`create_draft_tree` 绑定语义澄清、
  `contentJson` 注明 stringify、`parse_hearthstone_deck_code` 歧义澄清）
- [x] **T-105**: 暴露 get_card_group_manager MCP 工具（闭环 G-04，返回 bindings 含 binding id）

## 阶段四：架构基础设施（已确认设计，待实施）

- [x] **T-107**: CardConfigBindingTask Step 模式重构 ✅ 2026-07-10 完成
    - 现状：`execute()` 混合 4 数据源加载 + 2 assembler + Koin 注册，加功能需感知全部模块
    - 方案：抽取 `ConfigBindingStep` 接口，每数据源独立成 Step（WeightInfoStep / GroupIndexStep / PurposeStep /
      ComboStep），execute() 仅编排 + 注册
    - 不碰 ConfigDispatcher（两系统独立：Step 是 N→1 汇聚，ConfigDispatcher 是 1→N 分派）
    - 验收：`WeightHanderStrategy` 编译通过 + lint 0 错误（等价重构，逻辑未变；`McpToolDriverTest` 后续回归由运行期验证）
- [x] **T-108**: MatchState + 使用动作（UseAction）实施
    - **基础设计已确认（D-1/D-2）**：MatchState 新类挂 MyWarManage + GameLifecycle 重置 + Koin；使用动作改名 UseAction，RecordPlayAction 经 Koin DI 记录 cardId+groupIds；配置走 ConfigDispatcher
    - **D-1/D-2 已实施（2026-07-10）**：`MatchState`(`lin.domain.MatchState`，GameLifecycle.start 清整局+本回合、RoundLifecycle.start 清本回合；`recordCardPlayed` 按 `cardId()` 与 `groupIds()` 计入)；`RecordPlayAction`(`object`, `UseAfterStrategy`, Koin `by inject()` MatchState，在 `afterExtAction` 记录)；`MyWarManage.init` 创建并 Koin 注册 + `registerLifecycle(matchState)`；`UseDomain` 零改动（记录经 UseAfterAction 回调）。
    - **配置侧声明链路已落地（2026-07-11，①③）**：新增独立行为表 `card_group_behavior(binding_id, behavior_type, payload)`（行模型，加行为只加行不动主表 `card_group_binding`），承载 `USE_ACTION` / `OVERRIDE`（未来扩展）。配置侧只存**动作标识字符串**（如 `"RECORD_PLAY"`），引擎 `UseActionRegistry` 解析为 `UseStrategy` 对象，零硬编码。链路：configUi `GroupUseActionProvider`(Koin 单例，经 SPI `ModulesInfo` 被引擎加载) 读 DB → 引擎 `GroupUseActionBindInfoProvider`(SPI `BindInfoProvider`) 生成 `BindInfo(BindingGroupId(bindingId), UseConfig(useStrategyList = ids.map{UseActionRegistry.resolve}))` → `UseConfigHandler`→`addUseStrategy`→`RecordPlayAction`。已改：configUi `CardGroupRepository`/`CardGroupEntities`/`CardGroupService`/`ConfigUiStrategyProvidersInfo`；引擎 `BindingProviders`(加 `GroupUseActionProvider`)/`UseActionRegistry`(新)/`GroupUseActionBindInfoProvider`(新)/ServiceLoader 注册。零 lint。**② 已完成（2026-07-11）**：`card_group_binding.overrides` 列已迁到 `card_group_behavior.OVERRIDE` 行（行模型），且主表 overrides 列已彻底删除（无 fallback，用户确认无需兼容旧数据）。读：`loadAll`/`loadBindings` 从 `card_group_behavior.OVERRIDE` 行合并；写：`saveManager`/`saveBinding` 覆盖写行为表。引擎链路（`GroupIndexStep`→`provideBindingOverrides`→`loadBindingOverrides`→`loadAll`）零改动。`CardGroupBehaviorRepository` 新增 `findBehaviorsByManager(managerId,type)`/`deleteBehaviorsByManager(managerId,type)`（按类型清理、保留其他类型）；`CardGroupRepository` 委托暴露。同时修复阻塞编译的既有问题：`MatchState` 的 `WarInfo` 误导入 `lin.lifecycle`（应为 `lin.domain`）；`CardGroupService` 缺 Jackson `readValue` 扩展导入；`ConfigUiStrategyProvidersInfo`/`ModelsDefine` 的 `CardGroupRepository` 未升级为两参构造、`CardGroupBehaviorRepository` 缺导入。configUi(`-pl configUi -am`) 编译通过、lint 0。**④ 已完成（2026-07-11）**：USE_ACTION 配置侧声明闭环打通。`CardGroupBinding` 新增 `useActions: List<String>` 字段（与 `overrides` 并列，UI 编辑 → 经 `saveManager`/`saveBinding` 写入 `card_group_behavior.USE_ACTION` 行；`loadBindingsWithOverrides`/`loadAll` 回读）。`BindingEditorPane` 在行为编辑区新增"使用动作"勾选面板，选项来自 `UseActionRegistry.knownActionIds()`，勾选经 `WorkbenchState.updateBindingUseAction` → `WorkbenchStore.updateBindingUseAction` 写入 `currentBindings[idx].useActions`；`saveManager`/`saveBinding` 覆盖写 USE_ACTION 行（先按类型清再填）。至此分组行为统一入口（OVERRIDE 控件 + USE_ACTION 勾选）齐备，均落 `card_group_behavior` 表。configUi 编译通过、lint 0。**T-108 配置侧声明链路（①③ 读+② 写 overrides+④ UI 入口）已全部落地**；仅剩 D-3 读侧 DataSource（Q-2 未收敛）。
    - **判定/读侧方案未收敛（见 Q-2）**：如何用配置驱动表达"圣契减四费"这类对局条件判定，经多轮讨论未定（整局短路 / GDB_138费用 / 组合DataSource / (B)分组计数 / (X)静态加权 均被推翻），当前候选 (X') 通用二维规则表 vs (Y) 特例 Source
    - 验收：`McpToolDriverTest` 跑通 + 新 DataSource 可被 AI 管道引用
- [x] **T-109**: UseStrategy 归属重构（配置侧 A 类迁入 CardCombinedConfig）
    - **背景（2026-07-11 讨论定论）**：配置侧分组行为（`CardGroupBehavior`）数据源已由 `GroupBehaviorProvider` 统一，但消费端仍分两条链路：OVERRIDE → `CardCombinedConfig.useIntent`（`GroupIndexStep`→`UseIntentAssembler`，刻意设计，管默认值/统一入口）；USE_ACTION → `CardWeightInfo.useStrategy`（`GroupUseActionBindInfoProvider`→ConfigDispatcher→`addUseStrategy`，历史遗留）。散点根因=`UseStrategy` 仍挂 `CardWeightInfo`，未随 `CardCombinedConfig` 重构落位。
    - **可行性：可行，但非"搬字段"**。`useStrategy` 混了两类，迁移前必须拆清：
        - **A. 配置声明动作**（启动期只读，如 `RecordPlayAction` 经 `UseConfig.useStrategyList`）——与 `useIntent` 同构，**可干净搬进不可变 `CardCombinedConfig`**。
        - **B. 运行期注入动作**（运行时 mutate，`SkillFindStrategy.kt:36` `skill.useAfterStrategy.addSafe(this)` + `RuleTreeBinding.kt:162-169` intent 派生追加）——依赖 `CardWeightInfo`/`ComboCard` 可变，**不能塞进不可变 `CardCombinedConfig`**，天然属运行载体 `ComboCard`。
    - **三个障碍**：① `CardCombinedConfig` 明确不可变（class，"🌟 不可变复合配置元组"）与 B 类运行时追加冲突；② `ComboCard` 运行载体直接从 `cardWeightInfo?.useAfterStrategy` 复制（`ComboCard.kt:59-61`），改归属需改 ComboCard 构造入参；③ B 类本质是运行期行为注入、非配置，不属配置元组。
    - **正确形态**：A 类搬进 `CardCombinedConfig`（配置声明动作与 useIntent 并列）；B 类留 `ComboCard`（运行期注入）。修正前述"迁移后两 sinks 自然合并"的说法——**只有 A 类合并，B 类是另一回事**。
    - **重要发现（实施中）**：`UseConfig.useStrategyList` 不止配置侧在用——**编码侧** `ReleaseWar.kt:22` / `CleanWar.kt:64` 等 `WeightRule` 也返回 `UseConfig(useStrategyList=...)`，经 ConfigDispatcher→`UseConfigHandler` 写 `CardWeightInfo`。故 `UseConfigHandler` 的 `useStrategyList` 分支**保留**（服务 A2 编码侧），只把**配置侧 A1** 迁走。
    - **实施记录（2026-07-11）**：
        - `CardCombinedConfig` 加 `useStrategies: List<UseStrategy>`（A1 承载；before/after 同属一关注点不平铺，单一 list 由消费端按类型分流，贴合 `useIntent` 子聚合范式）。
        - `CardCombinedConfigBuilder` 加 `useStrategiesByCardId`，`build()` 按 `filterIsInstance` 拆分填入。
        - 新增 `GroupBehaviorStep`（Koin: `GroupBehaviorProvider`）：单次遍历，OVERRIDE→`builder.groupOverrides`、USE_ACTION→`builder.useStrategiesByCardId`（经 `UseActionRegistry.resolve`）。
        - `GroupIndexStep` 仅保留结构索引；`RuleModule` 注册 `GroupBehaviorStep`。
        - `ComboCard` 的 `useAfter/BeforeStrategy` 改为合并 `combinedConfig`（A1）+ `cardWeightInfo`（A2+B）的 `MutableList`，运行期 `SkillFindStrategy` 追加仍有效。
        - 删除 `GroupUseActionBindInfoProvider`（及其 SPI 注册行）——其职责已由 `GroupBehaviorStep` 接管，避免 A1 重复落到 `CardWeightInfo` 导致 `RecordPlayAction` 执行两次。
        - 生命周期关键点：`ConfigDispatcher` 是 Koin 懒加载单例，`init` 在 `RuleTreeBindingTask` 启动时才处理 BindInfoProvider；`CardConfigBindingTask.build()` 更早，故 A1 不能靠"构建时从 CardWeightInfo 复制"，必须走 Step 直接进 `CardCombinedConfig`（已如此实现）。
    - **收益**：配置侧行为（OVERRIDE + USE_ACTION 的 A1）共享同一 sink（builder/assembler + CardCombinedConfig）；新增行为类型只需在 `GroupBehaviorStep` 一处按 `is` 分流（或扩展 `findXxx`）。`CardCombinedConfig` 仅 +1 字段（`useStrategies`），before/after 不平铺，避免聚合根膨胀。编码侧 A2 与运行期 B 维持原路径不变。
    - **验收**：`mvn -pl configUi -am compile` 通过 + 全改动文件 `read_lints` 0；运行期完整验收归 `McpToolDriverTest`（configUi 模块，需 DB 环境），待用户确认是否跑。
