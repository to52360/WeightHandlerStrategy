# 任务追踪 - ai-config-validation

## 当前目标

V1 阶段 18 个 MCP 工具已全部暴露（详见 `ai-config-generator/TRACKER.md`）。本阶段聚焦：补全鲁棒性缺口、端到端实战验证、V2
范围规划，以及领域内容规划（编码类 rule/condition 定位、正交积木补齐、AI 能力边界确认）。

## 整体进度

- **当前任务**: T-104（评估） & T-106（待评估，见 Q-1） & 领域内容执行计划（P-1.x~P-3.x 待确认）
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

## 挂起暂不处理任务

（暂无）

## 待评估问题

- [ ] **Q-1**: 能力背景是否需要独立 MCP 工具（T-106 衍生）？是否只需写进 SKILL（纯文档引导）即可让 AI 建树前先查阅？若判定不需要，则回退
  `list_capability_background` 及其模型/service/测试改动，仅保留 SKILL 说明。当前暂保留实现，待复盘。

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

- **Phase A — 后端直调迭代（主力）**：直接在代码层实现真实规则/条件/正交积木；用 `McpToolDriverTest`(批注:
  不在这个文件继续走测试,这个文件太大了,先提取封装环境能力代码)（直接调 `McpToolProvider.call`，即模拟 MCP+agent）跑真实卡组数据（
  `AAEBAZ8F...`）验证与细节完善。循环：实现 → test → 看暴露/可生成情况 → 修正。
- **Phase B — 真实 MCP server 复跑**：内容稳定后重新 build/redeploy，用真实 `deck-config` server + agent 跑完整
  SOP，确认新能力被暴露、可被正确引用、边界无幻觉。
- 注意：`list_evaluator_leaf_kinds`→`list_capability_background` 重命名需重新构建部署才在真实 server 生效，Phase B 前需同步。

### 执行计划（待确认，尚未实施）

> 验收统一约定：每条实施类任务以 `mvn -pl configUi test -Dtest=McpToolDriverTest` 跑通为验收（真实卡组 `AAEBAZ8F...` 走完整
> SOP）。

#### 目的 1：编码类 rule/condition 定位与真实内容

| 编号    | 任务             | 内容                                                                                                                                       | 验收                 | 状态      |
|-------|----------------|------------------------------------------------------------------------------------------------------------------------------------------|--------------------|---------|
| P-1.1 | 盘点现有编码类能力面     | 查 `DemoRule.kt` / `ConditionDemo.kt` 现有规则/条件、参数类型（`IntValueArg`/`IntsValueArg`）、可访问卡牌上下文（`callCard.card.cost/attack/health/race/type` 等） | 产出能力面清单            | pending |
| P-1.2 | 设计真实规则/条件集     | 列候选规则（费用阈值加分、费用命中加分、攻防阈值、种族过滤等）与条件（费用上限、种族白名单、类型等），明确参数与卡牌上下文                                                                            | 产出设计清单（含参数 schema） | pending |
| P-1.3 | 决策落点（先讨论，不预设）  | 讨论：core 的 demos 是验证架构用的，是否保留/重构另说；真实内容**是否**及**落哪**（extWeightHandler 或其他）需先讨论决策，不预设结论                                                    | 决策记录               | pending |
| P-1.4 | 实现 + 验证（决策后实施） | 完成 P-1.2 设计 + P-1.3 决策后，再实现真实规则/条件并经 `McpToolDriverTest` 验证可被 AI 生成引用。**不急于为跑通 test 而先写真实代码**                                            | test 可生成引用、无崩溃     | pending |

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
