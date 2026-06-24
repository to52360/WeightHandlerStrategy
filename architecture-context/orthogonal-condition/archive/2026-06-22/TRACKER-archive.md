# 任务归档 - orthogonal-condition - 2026-06-22

> 归档日期: 2026-06-22
> 原位置: `g:\liw_work\jiaoBen\Deck-Plugin-Market\architecture-context\orthogonal-condition\TRACKER.md`

## 阶段二已完成任务 (T-009, T-013)

- [x] **T-009**: ScoreOperator Provider 化 (将内置评分算子注册表抽取为 `ScoreOperatorProvider` SPI)
- [x] **T-013**: 完善 `SourceScore` 参数反序列化 (解决 U-002: 独立 ObjectMapper / 测试矩阵，保障运行时安全)

## 阶段三及硬编码迁移已完成任务 (T-018, T-019, T-021)

- [x] **T-018**: 实现真实参数化过滤数据源 (如我方战场随从数量 `FriendlyMinionsCountSource`
  ，已改用多个高内聚映射体与原子化数据源，避免了万能大参数过滤对象的设计)
- [x] **T-019**: 深入验证 `sourceType` 简化的 UI 与解析边界限制 (针对 `EvaluatorLeafSourceType` 泄露 UI
  细节进行类型化隔离优化，规避了大量隔离适配层代码)
- [x] **T-021**: 深入研究参数化数据源 DataSource 评估各特定数据源的边界 (已完成：管道化重构已上线，通过 DataSource ->
  Transform -> Operator 彻底解耦了数据提取、多级过滤与判定逻辑)

## 挂起评估任务已落实 (T-004, T-014, T-015)

- [x] **T-014** (U-006): 限制 `toGuardScoreRule` 仅支持 `ConstantScore` 分支 (已修改限制，不允许条件通过 SourceScore
  直接算分以简化行为)
- [x] **T-015** (U-005): 验证 `RuleBuilder.scoreEffectType` 并完成其对象传入支持 (已实现对象传入，为工厂预设 ScoreEffect
  提供支持)
- [x] **T-004**: 迁移已有硬编码条件到正交体系 (实现两种方案同时并存与支持，暂不执行物理迁移)
