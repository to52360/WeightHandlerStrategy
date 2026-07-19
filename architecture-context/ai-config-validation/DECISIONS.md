# 决策记录 - ai-config-validation

> 历史详情请阅读 [ARCHIVE-INDEX.md](./ARCHIVE-INDEX.md)

## 决策清单（已全部锁定，详情已归档）

| ID  | 一句话结论                                                                                                                                                                                          |   状态    | 归档       |
|-----|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|:---------:|------------|
| D-1 | 新建 `MatchState` 跨回合状态容器挂 `MyWarManage`，`GameLifecycle` 管理重置                                                                                                                          | ✅ 已锁定 | 2026-07-16 |
| D-2 | `RecordPlayAction` 声明式 opt-in 记录打出，Koin 直接注入 MatchState，不走 UseDomain                                                                                                                 | ✅ 已锁定 | 2026-07-16 |
| D-3 | 正交管道新增 DataSource 读 MatchState，不扩展 RuleEnv                                                                                                                                               | ✅ 已锁定 | 2026-07-16 |
| D-4 | `CardConfigBindingTask` 拆为 Step 模式（N→1 汇聚），不混用 ConfigDispatcher（1→N 分派）                                                                                                             | ✅ 已锁定 | 2026-07-16 |
| D-5 | Q-2a 管道 `match_activity_events → weighted_activity_sum → gte(4)`，参数 `List<String>`+`Int`，首批仅 cardId 匹配                                                                                   | ✅ 已锁定 | 2026-07-16 |
| D-6 | 启发式打分升级为六分量模型：解耦 `PenaltyWeight`=3.0~3.5（防低费单卡负分）、增加 `comboPenalty` 组合消耗扣分与 `initiativeScore` 局势先手奖励                                                       | ✅ 已锁定 | 2026-07-19 |
| D-7 | 启发式打分升级为 V2.1：组合基础分引入衰减因子 λ=0.85、comboPenalty 升级为 (n-1)^1.5*0.7 非线性剧增、启动期校验 PenaltyWeight/CostWeight∈[0.55, 0.70]、空过基准设定为 Pass=0.0，彻底消除多卡凑费膨胀 | ✅ 已锁定 | 2026-07-19 |
