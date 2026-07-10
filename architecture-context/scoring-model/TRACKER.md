# 评分模型设计决策 - 任务追踪

> 背景：原 `ai-config-validation/TRACKER.md` 内容过多且与引擎核心评分无关，故将"卡牌基础分 + 剩余法力惩罚"单独立项。
> 源头讨论：2026-07-09 用户指出 (1) 评分计算规则不明确、缺基础分规则；(2) 剩余法力惩罚分散两处需统一。

## 当前目标

确立引擎核心评分模型（卡牌基础分 + 剩余法力惩罚），消除分散实现与一个已知负数 bug，使评分规则可解释、可调参。

## 现状要点（已盘点）

- 剩余法力惩罚写在**两处**，但语义不同（勿盲目合并）：
    - 路径 A（特殊预评估快路）：`WeightResult.kt:86-96` —— `isLessCost`（仅 1 张或总费<可用法力）时直接全收，再
      `extWeight -= (cost - costSum()) * CostWeight`。这是"怎么出都打不满"的退化快路。
    - 路径 B（真正子集择优）：`FindBestCombination.kt:104-112` —— 回溯选子集，
      `effectiveScore = currentWeight - remainingCost * CostWeight`，每节点都是候选。
    - 两者都用线性 `remaining * CostWeight`，这是后续要收敛的**计算原语**，但 route 逻辑不等同。
- 常量（`ComboDefValue.kt`）：`CostWeight=5.0`（一费=5权重）、`BaseWeight=1.0`（卡牌无配置默认分）、`MaxCostWeight=10.0`（*
  *死常量，全仓仅声明**）。
- 卡牌 `powerWeight` 默认 `BaseWeight=1.0`，**无"按费用给基础分"规则**；1 费怪与 10 费怪若都未配权重，基础分都是
  1，但惩罚都按真实费用 5/点算 → 量纲失衡（空 1 法力=5 权重≈5 张卡基础分），几乎强迫打满法力。
- 已知 bug：路径 A 下 `extWeight` 初始 0，可用法力 10 仅 1 张 1 费卡 → `extWeight=-45` 巨大负数（无替代组合仍扣分）；边界上
  `_canUseCardsByHandler` 为空且 `cost>0` 时 `isLessCost()` 返 true，算出奇异结果。

## 关键设计原则（2026-07-09 补充）

- **边际费用价值递减**：用户指出当前评分"太线性"——基础分（费用→分）与剩余惩罚（剩余→罚）都按费用直线计算，但现实中**越往后的费用价值越低
  **（高费卡并非低费卡的 N 倍价值；空 1 费比空 9 费更"亏"）。因此 `baseScore(cost)` 与 `remainingCostPenalty(remaining)`
  都应考虑**非线性/递减**映射，而非 `× CostWeight` 的直线。这同时强化了 S-0.1 的必要性：把惩罚收敛成**单一原语**
  ，未来线性→非线性的切换只改一处。
- **两条路径语义不同，勿盲目合并**：路径 A（`WeightResult.isLessCost` 分支）是"打不满法力"时的**特殊预评估快路**（直接全收），路径
  B（`DefaultFindBestCombination` 回溯）才是真正的**子集择优 + 剩余惩罚**。两者都含"剩余法力×CostWeight"，但 route
  语义不同；统一只收敛**惩罚计算原语**，不把两套逻辑等同。

## 已确认事实（2026-07-09 查代码）

- SDK `Card` 只暴露 `card.cost`（**当前/可能被对局改动过的费用**），全仓无 `baseCost`/`originCost` 字段。`ComboCard.cost()`
  即 `card.cost`。→ **"基础费用"无法从运行时 Card 正确取得**，只能来自静态卡定义。
- 静态卡定义在 `hs_cards.db`（`HsCardRepository` 读 `hs.cards` 表，目前只取 name/text/dbfId，未取 cost）。**`hs.cards` 含
  cost 列（用户 2026-07-09 确认可在 db 查到）**。`CardConfigBindingTask` 在启动期以 cardId 为键一次性组装
  `CardCombinedConfig`，是注入"基础费用→基础分"的天然位置。
- WHS 当前不读 `hs_cards.db`（只读本模块的 `weightHandlerStrategy.db`）。要取静态基础费用，需在 WHS 侧加一个按 cardId 查
  cost 的轻量查询，或让 SPI `CardWeightInfoProvide` 顺带提供。
- **运行时 `card.cost` 绝不可用于基础分（双理由，2026-07-09 用户强调）**：① 语义上是被对局改动过的费用；②
  若用作价值/基础分，引擎会"傻乎乎只出高费卡"。必须用**静态费用 + 凹函数**，且启动期烘焙、运行时零 db 命中。

## 评分模型（三分量，2026-07-09 用户澄清）

用户澄清 `powerWeight` 的真实语义：它是**超模额外评分**（固定值），与基础分是两回事。最终 effective weight 应为三分量相加：

    effectiveWeight = 基础分(baseScore) + 额外分(extraScore) + 规则分(ruleScore)

- **基础分 baseScore**：由卡牌费用派生（NEW），所有卡都有，来自静态 `hs_cards.db` cost 列，**启动期烘焙**进
  `CardCombinedConfig`。
- **额外分 extraScore**：即原 `CardWeightInfo.powerWeight` 的"超模溢价"，固定配置值；无配置则 = 0（**不再用 `BaseWeight=1.0`
  魔法默认**）。
- **规则分 ruleScore**：即 `ComboCard.extPowerWeight`，由权重规则/Combo 累加。

推论：所谓"混合"不是「配置 or 派生」二选一，而是**基础分恒在 + 额外分有则加之**，两者正交。未配置 `CardWeightInfo` 的卡 =
基础分 + 0 + 规则分。**运行时绝不查 db**（避免热路径 DB 命中），全部在启动期解析烘焙。

## 待决问题（按依赖排序，先决后决）

| #   | 问题                    | 决策方 | 结论                                                                                             | 状态           |
|-----|-----------------------|-----|------------------------------------------------------------------------------------------------|--------------|
| Q-1 | 基础分 vs 额外分关系：三分量相加    | 已确认 | 基础分恒由静态费用派生（启动期查 `hs_cards.db` 烘焙），额外分=原 `powerWeight`（无配置则 0），两者正交非二选一                        | ✅ 待 S-1.x 落地 |
| Q-2 | `baseScore(cost)` 公式  | 已确认 | `CostWeight * √cost`（凹，1→5 / 5→11.2 / 10→15.8）                                                 | ✅ 待 S-1.2 落地 |
| Q-3 | `CostWeight` 与基础分量纲关系 | 用户  | 与 Q-4 联合调参（需真实卡组跑一轮校准）                                                                         | 阻塞 S-2.x     |
| Q-4 | 惩罚非线性映射               | 已确认 | `remainingCostPenalty(remaining, total) = CostWeight * √remaining * ratio^β`，引入 totalCost 占比因子 | ✅ S-2.2 已落地  |

## 任务流程（编排）

> 原则：**先机械清理、后模型决策**。Phase 0 不依赖任何决策，先落地能立刻消除"分散"与 bug；Phase 1/2 才是需要你拍板的模型问题。

### 阶段 0：清理与统一（机械，无决策，建议先做）✅ 从这里开始

- [x] **S-0.1** 抽出**共享惩罚原语** `fun remainingCostPenalty(remaining: Int): Double`（当前内部
  `remaining * CostWeight`，预留为后续切换非线性的唯一改动点）。路径 A、B 调用同一原语，但**保留各自 route 语义**
  （A=预评估快路全收、B=回溯择优），不把两套逻辑等同合并。落点：`ComboDefValue.kt` 或 `WeightResult.kt` 旁。
- [x] **S-0.2** 修 `isLessCost` 负数 bug。安全方案：该分支无替代组合，惩罚改为"参与跨 plays 比较时做下限 clamp"或"
  不叠加惩罚"，先给最小安全修复，细节随 Q-3 再定。

### 阶段 1：基础分模型（三分量 + 启动期解析，需决策 Q-1/Q-2）

- [x] **S-1.1** 确认"三分量相加"模型（基础分恒在 + 额外分配置有则加）。基础分来源 = 静态 `hs_cards.db` cost 列，但*
  *不做启动期全库加载**（卡池几万、单卡组仅 ~50）；改为 `parseComboCard` 首次遇到某卡时按 cardId 查一次 `queryCardCostById`
  并缓存（`baseScoreCache`），每张卡一生只查一次、之后纯内存命中，绝不回退 `card.cost`。
- [x] **S-1.2** 定义 `baseScore(cost)` 公式（`CostWeight * √cost`）；`ComboCard` 改为
  `基础分 + 额外分(原powerWeight,缺省0) + 规则分(extPowerWeight)`，移除 `BaseWeight=1.0` 魔法默认；`parseComboCard` 注入
  `baseScore`（按需查 cost 并缓存，兜底 `?: 0.0`，绝不用 card.cost）。
- [ ] **S-1.3** 接线验证：未配置 `CardWeightInfo` 的卡 = 基础分 + 0 + 规则分；已配置卡额外加溢价；用真实卡组跑
  `McpToolDriverTest` 验证分数合理（**编译已通过，数值合理性待 Q-3 真实卡组校准**）。

### 阶段 2：量纲再平衡（需决策 Q-3/Q-4）

- [ ] **S-2.1** 设定 `CostWeight` 与基础分关系，确保高费强卡不被惩罚压死。
- [x] **S-2.2** 非线性惩罚已落地：`remainingCostPenalty(remaining) = CostWeight * √remaining`（与 baseScore 同一套凹变换），呼应
  Q-4。

### 阶段 3：验证

- [ ] **S-3.1** 用真实卡组（或新增针对性单元测试）验证分数合理：高费强卡不再无脑输给低费杂鱼、打满法力不再被过度奖励/惩罚。

## 从哪开始

**先做 S-0.1 + S-0.2**（机械清理，零决策风险，立刻让惩罚规则收敛到单点）。
与此同时我们并行讨论 **Q-1/Q-2**（基础分怎么给）——这是唯一需要你拍板、且目前最模糊的部分。

## 决策记录

- **2026-07-09 S-0.1 完成**：新增 `remainingCostPenalty(remainingCost)` 原语于 `ComboDefValue.kt`，路径 A（
  `WeightResult.kt`）、路径 B（`FindBestCombination.kt`）均改用，移除分散的 `remaining * CostWeight`。保留两 route 各自语义。零
  lint。
- **2026-07-09 S-0.2 完成**：`isLessCost` 负数 bug 已钳制——惩罚上限不超过本组合自身权重和（
  `remainingCostPenalty(lessCost).coerceAtMost(baseWeightSum)`），并 `coerceAtLeast(0)` 防负 lessCost、空组合跳过。最终量纲/是否保留待
  Q-3。
- Q-1~Q-4 结论在此沉淀（待定）
- **2026-07-09 模型确定**：三分量（基础分+额外分+规则分）相加；基础分= `CostWeight*√cost` 由静态 hs_cards.db cost 列*
  *按需解析+缓存（不启动期全库加载，卡池几万/卡组仅~50）**；额外分=原 powerWeight(缺省0)；规则分=extPowerWeight。惩罚
  `remainingCostPenalty=CostWeight*√remaining`。运行时 card.cost 双理由不可用（语义+偏好高费卡）。cost 列存在已确认。Q-1/Q-2/Q-4
  已确认，仅 Q-3(量纲校准) 待真实卡组跑。S-1.x 现已可纯接线落地。
- **2026-07-09 S-1.1/S-1.2/S-2.2 完成（落地）**：`CardInfoDao` 新增 `queryCardCostById(cardId)`（按 cardId 查单卡静态费用）；
  `parseComboCard` 首次遇到某卡时查一次 cost 算 `baseScore` 并缓存进 `baseScoreCache`(ConcurrentHashMap)，*
  *每张卡一生只查一次、绝不批量加载全库**；`ComboCard` 增 `baseScore` 参数，
  `powerWeight = baseScore + extraScore + extPowerWeight`，`isBaseWeight` 改为 `extraScore==0`；`parseComboCard` 注入
  `baseScore`（兜底 `?:0.0`，绝不用 card.cost）。`ComboDefValue` 增 `baseScore(cost)`、`remainingCostPenalty` 改凹函数。WHS
  编译通过、零 lint。S-1.3 数值合理性待 Q-3 真实卡组校准。
- **2026-07-09 评分原语改进**：
  - `baseScore` 由 `CostWeight * √cost` 改为 `CostWeight * cost^α`，新增 `ScoreExponent=0.5` 常量可调凹度。
  - `remainingCostPenalty` 签名改为 `(remainingCost, totalCost)`，引入占比因子：`CostWeight * √remaining * ratio^β`，新增
    `PenaltyRatioExponent=0.5` 常量。
  - 动机：绝对浪费不看占比导致前期/后期惩罚相同不合理（3/10=30% vs 3/5=60% 惩罚一样），加入 `totalCost` 后浪费占比越大惩罚越重；
    `ScoreExponent`/`PenaltyRatioExponent` 均可在 Q-3 校准时独立调参。
