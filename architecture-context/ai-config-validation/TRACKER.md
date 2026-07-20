# 任务追踪 - ai-config-validation

> 历史详情请阅读 [ARCHIVE-INDEX.md](./ARCHIVE-INDEX.md)
> 决策记录见 [DECISIONS.md](./DECISIONS.md)

## 当前目标

重新明确静态权重（配置）与动态规则（树）的架构边界，梳理真实有效的任务集。

## 整体进度

- **当前任务**: T-123 针对圣契卡组逐个分组模拟端到端实战（第 1 分组 `圣契减费引擎` 正交规则树已完成真实提交落盘，待第 2 分组
  `圣契法术` 演练与落库）
- **整体状态**: 🔄 进行中（第 1 分组正交规则树 treeId=2c3ec687 已落盘，等待推进第 2 分组）

## 任务列表

### 阶段一与基础设施（已完成，已归档）
- [x] **T-101 ~ T-105, T-107 ~ T-109, T-113**: 阶段一 V1 收尾与架构基础设施全部完成 (归档批次: 2026-07-13 / 2026-07-16)

### 阶段二：V2 范围规划 (批注:暂时挂起)
- [ ] **T-110**: Combo 编排 MCP 暴露规划（对应 T-022，需先梳理 ComboPlanDefinition 与评估树引用关系）
- [ ] **T-111**: 用途标签 MCP 暴露规划（对应 T-023，评估 PURPOSE_TAG 绑定类型 AI 可操作性）
- [ ] **T-112**: 配置生命周期管理评估（delete/disable/clone 是否暴露，还是保持 UI-only）

### 阶段三：架构与代码级重构任务（新梳理）
- [ ] **T-115**: 设计真实的动态推演条件与纯打分节点（去除伪属性匹配需求，专注 GameState 与 ScoreAction 的组件补齐）
- [ ] **T-116**: 结合最新实战验证落库数据，排查暴露的 UI/DB 新缺口（待填补）
- [x] **T-120 ✅ (2026-07-18)**: 支持评估树直接绑定单卡（`bindingType: CARD`），免去为单张卡特意创单卡分组的无意义操作
  - **实现细节**:
    1. 数据库/模型层扩展：让 `tree_config` 的 `binding_type` 允许存储 `"CARD"`，`binding_ids` 绑定 `cardId`（如 `["BT_020"]`）。
    2. 草稿创建工具 (`create_draft_tree`) 适配：若绑定类型为 `CARD`，校验 `bindingIds` 中的卡牌是否存在，且 `managerId` 变为非必填。
    3. 查询树工具 (`evaluator_tree`) 适配：确保单卡绑定的树能被正常检索（支持 `CARD` 绑定的 LIST 与 GET）。
    4. 实战测试：在 `DeckFlowStage3_SingleGroupTest.kt` 中添加单卡绑定建树测试以验证全流程。
- [x] **T-121 ✅ (2026-07-18)**: 重构属性配置对话框 `TreePropertiesDialog`。解决 init 构造块超限（约 200 行，多职责耦合）痛点，精简主逻辑至 70 行内并拆解为 6 个单一职责的 private 方法；同时修复了单卡类型切换失效与校验漏检逻辑瑕疵。
- [x] **T-122 ✅ (2026-07-18)**: 技能 `use-ai-config-generator/SKILL.md` 优化更新。增补评估树拓扑「缩进文字树状图」的可视化展现规范；并制定了“严格限制单个回合跑完，草稿骨架与最终提交必须分段经人类对齐确认”的配置生成 SOP，根绝 AI 数据倾倒和过度越权执行。
- [/] **T-123**: 针对圣契卡组逐个分组模拟端到端实战。按“单个分组/单树生成 -> 人机核验规则树质量 -> 调整/确认 ->
  满意后批量推进”的渐进流程进行演练与测试验证，以便及时检验 AI 生成规则树的质量与实战适配性。 *(批注: 第 1
  分组「圣契减费引擎」已完成正交规则树质量核验与提交落盘 (treeId=2c3ec687)，下一步推进第 2 分组「圣契法术」)*
- [x] **T-124 ✅ (2026-07-19)**: 正式消化 G-08 动态缺口，废弃并下沉 G-07。
  - **G-07 (已废弃/下沉)**：卡牌类型 (SPELL/MINION/WEAPON) 属于卡牌的静态属性，应当由静态卡牌分组（配置期）解决。不在动态规则树中设计此多余节点。
  - **G-08 (动态检测)**：注入基于炉石 GameState 真实状态（圣契打出+武器入墓加权求和 `weighted_activity_sum`、分组打出计数
    `pick_group_count`）的动态条件配装。将 325 行的 `DefaultComponents.kt` 拆解重构为 `DefaultDataSources.kt`、
    `DefaultTransforms.kt`、`DefaultOperators.kt` 三个高内聚文件；增补 `MatchTurnCountSource` (对局回合数) 并补齐
    `HandComboCardsSource` / `MeComboCardsSource` / `ToCardsTransform` 在 SPI 中的完整注册。
- [x] **T-125 ✅ (2026-07-19)**: 明确非线性评分模型与打分量规，并在代码层面完成系统重构。
    - **规则澄清**: 厘清基础分 (凹函数 `5*√cost`)、非线性剩余惩罚 (解耦 `PenaltyWeight=3.5`，放弃 2 费只扣约 4.5 分而非 10
      分，消除低费单卡负分偏见)、组合复杂性惩罚 (`comboPenalty` 每多打 1 张卡扣 0.5 分)、静态额外分 (扩展至 `-3.0~+5.0`)
      与动态规则分 (主力级 6.0~10.0 分)的六分量正交关系，并已沉淀至 `use-ai-config-generator/SKILL.md` 与
      `scoring_rule_evaluation_reference.md (V2.0)` 指导书。
    - **代码落地**: 在 `EngineConfig.kt` / `engine.properties` 中添加 `penalty.weight=3.5` 与 `combo.card.weight=0.5`；在
      `ComboDefValue.kt` 中解耦 `remainingCostPenalty` 并新增 `comboPenalty` 原语；在 `FindBestCombination.kt` 与
      `WeightResult.kt` 中将 `comboPenalty` 纳入最佳组合搜索算法；在 `ScoringModelTest.kt` 中完成 45 项自动化单元与集成测试。
- [x] **T-126 ✅ (2026-07-19)**: 彻底重构 `WarStatus` 旧局势组件为正交 `WarView` 管道拓扑。
  - **架构解耦**：以 `WarViewSource` (`war_view`) 作为唯一战场局势 DataSource 入口，将 `excess_damage` (突破嘲讽溢出伤害)、
    `acceptable_attack` (可承受攻击上限)、`rival_cards_from_view`、`me_cards_from_view` 纯粹化重构为基于 `WarView` 的管道转换器
    (Transforms)。
  - **组件增补与 SPI**：补齐 `MatchTurnCountSource` (结合 MatchState 突破 10 水晶限制)、`GroupFilterTransform`、
    `PurposeFilterTransform`、`IsCardTypeOp` 等常用正交积木与全量 SPI 注册。
  - **自动化测试**：新增 `DefaultOrthogonalComponentsTest.kt`，全项目 38 项 Maven 单元测试编译 100% 校验通过。
- [x] **T-118 ✅ (2026-07-17)**: MCP delete 工具补 G-09 缺口。
  - `delete_card_group(managerId)` — 级联删 manager+bindings+关联树，返回完整清单
  - `delete_evaluator_tree(treeId)` — 删单棵树，无效 ID 返回错误+现有树列表防幻觉
- [x] **T-119 ✅ (2026-07-17)**: MCP 工具合并优化 — 22→17 工具。
  - 5 组 list+get 对按 action 合并：`card_pool`、`card_group`、`template_browse`、`tree_template`、`evaluator_tree`
  - 遵循优化提示词 §4 合并规则 + §5 不隐藏能力原则

## 挂起暂不处理任务

| 编号  | 内容                                                                                                                 | 触发条件                                                 |
|-------|----------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------|
| T-104 | 评估 `validate_leaf_config` 独立 MCP 工具必要性                                                                      | AI put 频繁失败重试时启动                                |
| T-106 | `list_capability_background` 实施完成，待评估是否回退（见 Q-1）                                                      | Q-1 结论产出后联动                                       |
| Q-1   | 能力背景是否需要独立 MCP 工具（T-106 衍生）                                                                          | 复盘时决策                                               |
| Q-2c  | 正交管道参数 UI 多选 + 上下文数据源（Q-2a 衍生）                                                                     | 人类 UI 配置体验变痛点时启动                             |
| Q-3   | UseActionPane 声明式子面板绑定                                                                                       | 后续 action 增多 if-else 膨胀时启动                      |
| Q-4   | 讨论静态引擎底噪计分规则（超模一费给5分等）是否合理/如何讨论                                                         | 推进动态权重实战演练时讨论                               |
| D-002 | `WeightedActivitySumTransform` + `MatchActivityEventsSource` 多数据源重构（@defect D-002，职责混合：求和+匹配+缓存） | 再有 Transform 内嵌事件匹配判定+缓存的同类案例出现时启动 |

## 实战验证与缺口记录

- [x] **2026-07-08 / 2026-07-09**: V1 端到端实战验证跑通，旧缺口 G-01~G-06 均已修复或确认为架构内建支持。(详情见 TRACKER-archive.md)
- [x] **T-117 ✅ (2026-07-17)**: 新一轮实战演练 — 圣契圣骑士卡组端到端验证 (v2 策略角色分组)。
  - **卡组**: 圣契圣骑士 (AAEBAZ8F...), 17 张卡
  - **v2 分组**: 领域知识驱动 6 组 — 圣契减费引擎/圣契法术/过牌检索/干扰拖延/解场清理/制胜终端
  - **建树**: 6 棵评估树全部创建 commit
  - **待消化缺口**:
    1. **G-07 卡类型筛选（已废弃/下沉）**：卡牌类型属于静态属性，不再作为动态条件塞入规则树中。应当直接在第 2 步静态分组阶段完成划分。
    2. **G-08 GameState 动态规则空白**：需要利用 D-3+D-5 设计基于局势（如费用减免）的真实动态条件。
  - **id 追踪**: `data/cardgroup/.tracked/real_libram_deck.json`
