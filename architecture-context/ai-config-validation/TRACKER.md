# 任务追踪 - ai-config-validation

> 决策记录见 [DECISIONS.md](./DECISIONS.md)

## 当前目标

V1 阶段 18 个 MCP 工具已全部暴露（详见 `ai-config-generator/TRACKER.md`）。本阶段聚焦：补全鲁棒性缺口、端到端实战验证、V2
范围规划，以及领域内容规划（编码类 rule/condition 定位、正交积木补齐、AI 能力边界确认）。

## 整体进度

- **当前任务**: T-104（评估） & T-106（待评估，见 Q-1） & 领域内容执行计划（P-1.x~P-3.x 待确认） & T-107/T-108（架构基础设施，已确认设计）
- **整体状态**: 🔄 进行中
- **另有**：误实施代码回退清单待执行（见专属段）

## 任务列表

### 阶段一：V1 收尾（MCP 工具闭环与实战验证）

- [x] **T-101**: 暴露 get_draft_status MCP 工具（`DraftTreeService.getDraftStatus()` +
  `AiDraftTreeToolProvider.typedTool<GetDraftStatusRequest>`，支撑断点续传）
- [x] **T-102**: 端到端实战验证（`McpToolDriverTest` 直接调 `McpToolDriverTest` 跑通 19 工具全链路 SOP，见「参考：实战验证记录」）
- [x] **T-103**: 根据实战反馈优化工具描述（`list_orthogonal_components` 补 in/out 类型签名、`create_draft_tree` 绑定语义澄清、
  `contentJson` 注明 stringify、`parse_hearthstone_deck_code` 歧义澄清）
- [ ] **T-104**: 评估 validate_leaf_config 独立工具的必要性（若 AI 频繁因 put 失败重试成本高则暴露，否则不做）
- [x] **T-105**: 暴露 get_card_group_manager MCP 工具（闭环 G-04，返回 bindings 含 binding id）
- [x] **T-106**: 能力背景暴露（`list_capability_background`）— 实施完成，待评估是否回退（见 Q-1）

### 阶段二：V2 范围规划（不编码）

- [ ] **T-110**: Combo 编排 MCP 暴露规划（对应 T-022，需先梳理 ComboPlanDefinition 与评估树引用关系）
- [ ] **T-111**: 用途标签 MCP 暴露规划（对应 T-023，评估 PURPOSE_TAG 绑定类型 AI 可操作性）
- [ ] **T-112**: 配置生命周期管理评估（delete/disable/clone 是否暴露，还是保持 UI-only）

### 阶段三：领域内容规划（执行计划，待确认）

- [ ] **P-1.x / P-2.x / P-3.x**: 目的 1/2/3 共 9 项子任务，全部 pending，详见「领域内容规划 → 执行计划」段

### 阶段四：架构基础设施（已确认设计，待实施）

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
    - **正确形态**：A 类搬进 `CardCombinedConfig`（配置声明动作与 useIntent 并列）；B 类留 `ComboCard`（运行期注入）。修正前述"迁移后两 sink 自然合并"的说法——**只有 A 类合并，B 类是另一回事**。
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

## 挂起暂不处理任务

（暂无）

## 待评估问题

- [ ] **Q-1**: 能力背景是否需要独立 MCP 工具（T-106 衍生）？是否只需写进 SKILL（纯文档引导）即可让 AI 建树前先查阅？若判定不需要，则回退
  `list_capability_background` 及其模型/service/测试改动，仅保留 SKILL 说明。当前暂保留实现，待复盘。

- [ ] **Q-2**: 「圣契减四费」对局条件判定的配置驱动建模（T-108 读侧，未收敛，2026-07-10 多轮讨论无结论，待外部 AI/用户决策）
  - **目标**：用配置驱动（零硬编码）表达"对局条件达成后本局恒定成立（latch）"，供 card-level 规则引用。例子=圣契圣骑的"圣契减四费"。
  - **业务本质（用户澄清）**：圣契减四费 = 按 **(卡类型 × 事件类型) → 权重** 的二维加权求和 `>= 4`，且：
    - 武器：在**墓地**=权重2、在**使用(打出)**=权重1（同一卡类型，位置不同权重不同）
    - 随从：只有**死亡**才进墓地（与武器"用完进墓地"语义不同，"进墓地"信号因卡类型含义不一致，无法统一判定）
    - 一张卡可属于多个分组，使用判断与墓地判断都要计入其所有所属组
  - **被否决方案及原因**：
    1. 整局短路停止出牌决策 —— 误解需求（用户要"记录条件状态"，非"停止计算"）
    2. 判定信号 GDB_138(4费HOLY) currentCost==0 —— 卡没抽到手上读不到 currentCost，致命
    3. 组合 DataSource `group_activity_count` —— 每个组合方式硬写一个，组合爆炸
    4. (B) 通用分组活动计数 `Map<groupId,Int>` —— 武器减2费/随从减1次，单位与权重不统一，无法简单合并
    5. (X) 减费贡献值=卡静态属性(KV) —— 权重实际是 (卡类型×位置) 二维动态，非静态属性
  - **当前两个候选**：
    - **(X') 通用二维规则表**：`(cardType, eventType) → weight` 规则配置（仍配置驱动、通用不每特例加表）；通用 `weighted_sum` Source 分两类事件聚合——使用事件(MatchState 记录的打出)、墓地事件(墓地查询)，按规则查权重求和 `>= 4`。彻底零硬编码但 Source 实现稍复杂（要同时聚合两类事件来源）。
    - **(Y) 圣契减四费特例 Source**：武器/随从规则直白写在一个专门 evaluator，逻辑好读但略硬编码（只覆盖圣契这一局）。
  - **待决策问题清单（给接手者）**：
    1. 该业务是否值得通用框架表达，还是接受为业务特例 (Y)？
    2. 若选 (X')，规则表 `(cardType, eventType)→weight` 的存储/配置形态（复用 PurposeTag 通用卡元数据体系 or 新建 KV）？
    3. MatchState 到底记录什么（只记"打出事件"供 use_event，还是也记其他）？
    4. 两类事件来源如何统一聚合（使用事件来自 MatchState，墓地事件来自墓地查询 `WarInfo.getGraveyardCards()`）？
    5. 读侧 `match_flag(conditionId)` 如何引用判定结果（latch 进 `MatchState.achievedFlags`，conditionId 配置）？
  - **强约束（贯穿全程）**：绝对零硬编码——任何业务值（卡名 GDB_726、业务名"圣契减四费"、权重、阈值）严禁写死进 Kotlin；必须用正交管道（无参 DataSource + 参数化 Transform/Operator，参数来自配置 args）。DataSource 接口本身无 args 字段，参数化只能走 Transform/Operator。
  - **现成能力**：`AttackWarInfoExt.kt` 已有 `getGraveyardCards()` 系列；正交管道 DataSource 无参输出集合（如 hand_cards），过滤走参数化 Transform（参考 `RaceFilterTransform` 的 `race` 参数）；`PipelineRef` 是单 Source 线性流，不支持两源数值相加（这是表达 A+B>=4 的原生缺口）。

## 误实施代码回退清单（待执行）

> 背景：此前为"让 `McpToolDriverTest` 跑通以验证目的"误实施一批代码（把验证架构的 demo 改成真实逻辑、新增算子），动机与用户"
> 先讨论确定编码类定位"的目的不一致且未经讨论。用户 2026-07-08 拍板全部回退，保持纯计划。

- **WHS 3 文件（tracked，仍含误实施改动，尚未回退）**：
    - `WeightHanderStrategy/.../useDemo/DemoRule.kt`（2 个规则被改真实费用逻辑 → 恢复为验证架构 demo 原样）
    - `WeightHanderStrategy/.../orthogonal/DefaultComponents.kt`（新增 `EqualOp`/`LessThanOp` → 移除）
    - `WeightHanderStrategy/.../orthogonal/DefaultComponentsProvider.kt`（注册 2 新算子 → 移除）
    - 回退命令：`git restore` 上述三文件（HEAD 即 demo 原样），执行后重新 `install` WeightHandlerStrategy 使本地库纯净。
- **`McpToolDriverTest.kt`（untracked，暂不纳入 git 管理，保留现状）**：含误实施的 coded rule 验证块（`codedTreeId` /
  `verify_coded_1` / `cleaf1`），暂不处理；待讨论 P-1.x 定位后再决定是否移除该块或纳入版本控制。
- **T-106 相关改动不在回退范围**：维持"暂保留待评估"原状。

## 领域内容规划（背景与执行计划）

### 背景与当前能力面（源码盘点）

- 编码类规则：`typed_simple_rule`、`list_simple_rule`（`DemoRule.kt`，factory 体为 `TODO` 桩，无实际逻辑）
- 编码类条件：`max_cost`、`race_whitelist`（`ConditionDemo.kt`，有真实逻辑但极简、标注 demo）
- 正交积木：`DefaultComponentsProvider` + `DefaultScoreOperators` —— DataSource×4（Me/Rival/Board/Hand
  卡）、Transform×2（RaceFilter/CountProjection）、Operator×2（gte/ContainsRace）、ScoreOperator×3（identity/linear/reverse_linear）
- `extWeightHandler`（应为真实策略插件扩展模块）：0 注册，空壳 SPI 骨架

### 核心边界认知（指导全流程）

AI 生成配置 = 只能从**固定能力注册表**组装，不能扩展注册表本身。规则/条件/正交积木属于**代码/SPI 层**。因此目的 1/2/3
本质是领域建模 + 写代码，MCP 工具仅用于"暴露 + 存配置 + 验证"。这印证：先用分析/代码定内容，再用 MCP 验证。

### 三个目的

1. **编码类 rule/condition 定位**：demos 留 core 当架构样板；真实内容落点待讨论（不预设落
   extWeightHandler）。确定真实规则/条件集及其参数、卡牌上下文。
2. **正交缺哪些积木**：对照真实炉石策略，枚举缺失
   DataSource（牌库/法力/英雄等）、Transform（攻防投影/求和最值/类型过滤）、Operator（等于/小于/区间/存在）、ScoreOperator（阈值分段/布尔转分）。
3. **确认 AI 生成配置的能力边界**（见核心边界认知）。

### 执行工作流（用户拍板：不走 MCP 优先，后端直调快迭代）

- **Phase A — 后端直调迭代（主力）**：直接在代码层实现真实规则/条件/正交积木；用测试直调 `McpToolProvider.call` 跑真实卡组数据（
  `AAEBAZ8F...`）验证与细节完善。循环：实现 → test → 看暴露/可生成情况 → 修正。
    - ✅ 已提取公共环境 `McpTestEnv.kt`，拆出 `DeckSopFullFlowTest.kt` 按 SOP 走完整编组→建树流程（2026-07-09）
- **Phase B — 真实 MCP server 复跑**：内容稳定后重新 build/redeploy，用真实 `deck-config` server + agent 跑完整
  SOP，确认新能力被暴露、可被正确引用、边界无幻觉。
- 注意：`list_evaluator_leaf_kinds`→`list_capability_background` 重命名需重新构建部署才在真实 server 生效，Phase B 前需同步。

### 执行计划（待确认，尚未实施）

> 验收统一约定：每条实施类任务以 `mvn -pl configUi test -Dtest=McpToolDriverTest` 跑通为验收（真实卡组 `AAEBAZ8F...` 走完整
> SOP）。

#### 目的 1：编码类 rule/condition 定位与真实内容

| 编号    | 任务             | 内容                                                                                                                                       | 验收                 | 状态             |
|-------|----------------|------------------------------------------------------------------------------------------------------------------------------------------|--------------------|----------------|
| P-1.1 | 盘点现有编码类能力面     | 查 `DemoRule.kt` / `ConditionDemo.kt` 现有规则/条件、参数类型（`IntValueArg`/`IntsValueArg`）、可访问卡牌上下文（`callCard.card.cost/attack/health/race/type` 等） | 产出能力面清单            | ✅ (2026-07-09) |
| P-1.2 | 设计真实规则/条件集     | 列候选规则（费用阈值加分、费用命中加分、攻防阈值、种族过滤等）与条件（费用上限、种族白名单、类型等），明确参数与卡牌上下文                                                                            | 产出设计清单（含参数 schema） | pending        |
| P-1.3 | 决策落点（先讨论，不预设）  | 讨论：core 的 demos 是验证架构用的，是否保留/重构另说；真实内容**是否**及**落哪**（extWeightHandler 或其他）需先讨论决策，不预设结论                                                    | 决策记录               | pending        |
| P-1.4 | 实现 + 验证（决策后实施） | 完成 P-1.2 设计 + P-1.3 决策后，再实现真实规则/条件并经 `McpToolDriverTest` 验证可被 AI 生成引用。**不急于为跑通 test 而先写真实代码**                                            | test 可生成引用、无崩溃     | pending        |

#### 目的 2：正交积木补齐

| 编号    | 任务        | 内容                                                                                                                                | 验收             | 状态      |
|-------|-----------|-----------------------------------------------------------------------------------------------------------------------------------|----------------|---------|
| P-2.1 | 枚举缺失积木    | 对照真实炉石策略，列缺失 DataSource（牌库/法力曲线/英雄技能/手牌费用分布）、Transform（攻防投影/种族投影/求和最值/类型过滤增强）、Operator（等于/小于/区间/存在）、ScoreOperator（阈值分段/布尔转分/线性增强） | 产出缺失清单         | pending |
| P-2.2 | 分批实现 + 验证 | 按 P-2.1 分批实现，重点验证**类型链路兼容**（`DataSource→Transform→Operator` in/out 类型）                                                            | test 通过、类型链路正确 | pending |

#### 目的 3：确认 AI 生成配置的能力边界

| 编号    | 任务                | 内容                                                                   | 验收                   | 状态      |
|-------|-------------------|----------------------------------------------------------------------|----------------------|---------|
| P-3.1 | 能力边界基线            | 用 `McpToolDriverTest` 打印"当前暴露能力面"，作为新增内容的回归基线                        | 能力面快照                | pending |
| P-3.2 | 明确边界语义            | 文档化：AI **只能从固定能力注册表组装，不能扩展注册表**（规则/条件/正交属代码/SPI 层）                   | 边界说明写入 SKILL/TRACKER | pending |
| P-3.3 | Phase B 真实 MCP 复跑 | 内容稳定后重新构建部署，真实 `deck-config` server + agent 跑 SOP，确认新能力暴露、可被正确引用、无幻觉 | 端到端无幻觉               | pending |

## 参考：已知缺口与实战验证

### 已知缺口（G-01~G-04）

| #    | 缺口                                               | 严重度 | 状态          |
|------|--------------------------------------------------|-----|-------------|
| G-01 | `get_draft_status` 未暴露 MCP（AI 断连后无法恢复草稿进度）       | 中   | 已闭环（T-101）  |
| G-02 | 无删除工具（delete_evaluator_tree / delete_card_group） | 低   | UI 可手动删，暂挂起 |
| G-03 | `validate_leaf_config` 未独立暴露                     | 低   | 待评估（T-104）  |
| G-04 | 无工具查已有卡组分组的绑定条目 ID（bindingIds）                   | 中   | 已闭环（T-105）  |

### V1 闭环现状（5 阶段 SOP）

| SOP 阶段           | 工具数              | 状态                          |
|------------------|------------------|-----------------------------|
| 1. 元数据探查         | 2                | ✅                           |
| 2. 前置依赖供给（卡池+分组） | 5                | ✅                           |
| 3. 参考模板与存量配置     | 7                | ✅                           |
| 4. 渐进式生成（草稿池）    | 3                | ✅（T-101 补 get_draft_status） |
| 5. 资产沉淀          | 复用阶段 3 的 save 工具 | ✅                           |

### 实战验证记录（T-102，2026-07-08）

通过 `McpToolDriverTest` 直接调 `McpToolProvider.call`（绕过 JSON-RPC）跑通 19 个工具全链路，**无运行期崩溃**。暴露的真实缺口：

1. **正交组件缺少类型签名（最重要，归 T-103）**：`list_orthogonal_components` 未暴露输入/输出类型，AI 无法预判 `hand_cards`(
   输出 `List<Card>`) 直接接 `gte`(期望 `Int`) 类型不匹配，实跑报 `PIPELINE_TYPE_MISMATCH`。正确组合需
   `hand_cards → count_projection → gte`。
2. **`bindingIds` 与 `managerId` 语义混淆（归 T-103）**：GROUP 绑定要求 `managerId` 必填，且 `bindingIds` 必须是
   `save_card_group` 返回的绑定条目 ID（非 managerId）。AI 极易混淆，实跑报 `manager_id_missing` +
   `binding_group_not_found`。
3. **`contentJson` 是 String 型（归 T-103）**：`save_evaluator_tree_template` / `save_template` 需传 stringify 后的 JSON
   字符串，描述易被误读传嵌套对象，实跑报反序列化失败。
4. **`parse_hearthstone_deck_code` 次要歧义（可选）**：`heroes` 仅返回 dbfId（无职业名映射）；`totalCardsInCode` 为去重后种类数（本例
   17），易被误读为 30 张整卡。

> 注：本验证在 configUi 模块运行，DB 相对路径通过 `@BeforeClass` 的 `../hs_cards.db`、`../weightHandlerStrategy.db`、
`../data/cardgroup` 指向仓库根；Maven 用 `mvn -pl configUi test -Dtest=McpToolDriverTest`（WeightHandlerStrategy 已
`install` 到本地库）。

## 实战验证：SOP 编组→建树（2026-07-09，DeckSopFullFlowTest）

用 `AAEBAZ8F...`（圣契圣骑士，17 张卡）走完整 SOP：

### 卡牌分析

通过 DB 查询补齐 cost/type/attack/health/race 属性后，形成 5 组：

| 分组   | 卡牌                                                        | 费用区间 |
|------|-----------------------------------------------------------|------|
| 圣契引擎 | BT_020(1费随从), GDB_726(3费武器), GDB_728(2费随从), TID_077(9费随从) | 1~9  |
| 神圣法术 | BT_025(2费HOLY), GDB_137(3费HOLY), GDB_138(4费HOLY)          | 2~4  |
| 过牌   | BOT_909(1费), ETC_418(2费)                                  | 1~2  |
| 解场   | UNG_961(0费)~WW_336(7费) 共5张                                | 0~7  |
| 独立随从 | ICC_820(4费亡灵), VAC_507(5费), TID_098(3费纳迦)                 | 3~5  |

### 建树结果

5 组各建评估树（typed_simple_rule(limit=3) + 正交 hand_cards≥1 守卫），全部 commit 成功。

### 缺口（Pn=优先级）

| #    | 缺口                                                  | 影响                                          | Pn                 |
|------|-----------------------------------------------------|---------------------------------------------|--------------------|
| G-05 | `get_card_group_detail` 不返回 cost/type/attack/health | AI 无法按卡牌属性智能分组                              | ✅ 已闭环 (2026-07-09) |
| G-06 | 无 cardId 集合匹配的编码规则（typed/list_simple_rule 只做费用匹配）   | 不能说"这组卡加分" (批注: 卡的基础属性归.cardGroup文件管,不归规则归) | 🔴 P-1             |
| G-07 | 正交管道无自定义分组筛选 Transform（仅 race_filter）               | 不能"手牌里本分组卡≥2"                               | 🟡 P-2             |
| G-08 | 正交管道无 current_card DataSource                       | 不能"当前卡是随从则加分"                               | 🟡 P-2             |
| G-09 | 编码规则仅 2 种                                           | 策略表达力极有限                                    | 🟡 P-1             |
| G-10 | `equal`/`less_than` 正交算子来自误实施代码未回退                  | 能力面不纯                                       | ⚠️ 回退清单            |
