# 任务归档 - ai-config-validation - 2026-07-21

> 归档日期: 2026-07-21
> 原位置: `architecture-context/ai-config-validation/TRACKER.md`

### 阶段三：架构与代码级重构任务（已完成部分）

- [x] **T-120 ✅ (2026-07-18)**: 支持评估树直接绑定单卡（`bindingType: CARD`），免去为单张卡特意创单卡分组的无意义操作
    - **实现细节**:
        1. 数据库/模型层扩展：让 `tree_config` 的 `binding_type` 允许存储 `"CARD"`，`binding_ids` 绑定 `cardId`（如
           `["BT_020"]`）。
        2. 草稿创建工具 (`create_draft_tree`) 适配：若绑定类型为 `CARD`，校验 `bindingIds` 中的卡牌是否存在，且 `managerId`
           变为非必填。
        3. 查询树工具 (`evaluator_tree`) 适配：确保单卡绑定的树能被正常检索（支持 `CARD` 绑定的 LIST 与 GET）。
        4. 实战测试：在 `DeckFlowStage3_SingleGroupTest.kt` 中添加单卡绑定建树测试以验证全流程。
- [x] **T-121 ✅ (2026-07-18)**: 重构属性配置对话框 `TreePropertiesDialog`。解决 init 构造块超限（约 200 行，多职责耦合）痛点，精简主逻辑至
  70 行内并拆解为 6 个单一职责的 private 方法；同时修复了单卡类型切换失效与校验漏检逻辑瑕疵。
- [x] **T-122 ✅ (2026-07-18)**: 技能 `use-ai-config-generator/SKILL.md`
  优化更新。增补评估树拓扑「缩进文字树状图」的可视化展现规范；并制定了“严格限制单个回合跑完，草稿骨架与最终提交必须分段经人类对齐确认”的配置生成
  SOP，根绝 AI 数据倾倒和过度越权执行。
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
    - **架构解耦**：以 `WarViewSource` (`war_view`) 作为唯一战场局势 DataSource 入口，将 `excess_damage`
      (突破嘲讽溢出伤害)、`acceptable_attack` (可承受攻击上限)、`rival_cards_from_view`、`me_cards_from_view` 纯粹化重构为基于
      `WarView` 的管道转换器 (Transforms)。
    - **组件增补与 SPI**：补齐 `MatchTurnCountSource` (结合 MatchState 突破 10 水晶限制)、`GroupFilterTransform`、
      `PurposeFilterTransform`、`IsCardTypeOp` 等常用正交积木与全量 SPI 注册。
    - **自动化测试**：新增 `DefaultOrthogonalComponentsTest.kt`，全项目 38 项 Maven 单元测试编译 100% 校验通过。
- [x] **T-127 ❌ (2026-07-20 实施 / 2026-07-21 废弃)**: ~~实施 D-9 正交组件 memoize 装饰器方案（统一缓存基础设施）~~。 **被
  T-128 取代**。废弃原因：① `WarInfoEnv.memoizeStore()` 每次返回新实例，缓存永远 miss（致命 bug）；② `MemoizeCacheTest.kt`
  从未创建，bug 6 天未发现；③ 90 行基础设施 + 24 标注用于消除微秒级计算，过度工程化；④ `dependsOnCallCard`
  脚枪。详见 [DECISIONS.md D-9](./DECISIONS.md) / [D-11](./DECISIONS.md)。
- [x] **T-128 ✅ (2026-07-21)**: 删除 D-9 memoize 装饰器全套，替换为评估级
  Map（D-11）。实施计划见 [references/T-128-implementation-plan.md](./references/T-128-implementation-plan.md)。
- [x] **T-129 ✅ (2026-07-21)**: MatchActivityEventsSource
  引用化重构。实施计划见 [references/T-129-implementation-plan.md](./references/T-129-implementation-plan.md)。
