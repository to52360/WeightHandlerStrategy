# 任务追踪 - orthogonal-condition

>
历史详情请阅读 [ARCHIVE-INDEX.md](file:///g:/liw_work/jiaoBen/Deck-Plugin-Market/architecture-context/orthogonal-condition/ARCHIVE-INDEX.md)

## 整体进度

- **当前任务**: T-018 真实参数化过滤数据源 (FriendlyMinionsCountSource)
- **整体状态**: 🔄 进行中

---

## 任务列表

### 阶段一：正交条件与 DataSource (已完成)

- [x] **T-001 ~ T-003**: 阶段一及阶段三动态渲染已完成任务 (归档批次: 2026-06-16)

### 阶段二：评分效应 (ScoreEffect + ScoreOperator) 重构 (已完成)

- [x] **T-007 ~ T-008**: 阶段二已完成任务（ScoreEffect 最小模型与元数据表单接入） (归档批次: 2026-06-16)
- [x] **T-009**: ScoreOperator Provider 化 (将内置评分算子注册表抽取为 `ScoreOperatorProvider` SPI)
- [x] **T-013**: 完善 `SourceScore` 参数反序列化 (解决 U-002: 独立 ObjectMapper / 测试矩阵，保障运行时安全)

### 阶段三：硬编码迁移与 AI 配置适配 (进行中)

- [x] **T-016**: 规则 (Rule) 行为正交化配置窗口及强类型关联任务已归档 (归档批次: 2026-06-16)
- [x] **T-018**: 实现真实参数化过滤数据源 (如我方战场随从数量 `FriendlyMinionsCountSource`，支持传入种族 CardRaceEnum
  参数以实现战场精确判定)
- [x] **T-019**: 深入验证 `sourceType` 简化的 UI 与解析边界限制 (评估将 `sourceType` 物理合并为 RULE/CONDITION 后的 UI
  联动复杂度与 Jackson 物理层防污染限制)
- [ ] **T-021**: 深入研究参数化数据源 DataSource 的泛型与参数化设计边界（评估各特定数据源的边界、命名一致性及性能开销，防止在未来扩展中出现概念扩散）

---

## 挂起暂不处理任务 (由用户 2026-06-15 确认挂起)

- [ ] **T-014** (U-006): 限制 `toGuardScoreRule` 仅支持 `ConstantScore` 分支 (暂时无法抉择是否允许条件通过 SourceScore
  直接算分，代码暂不做改动，维持支持)
- [ ] **T-015** (U-005): 验证 `RuleBuilder.scoreEffectType` 是否支持对象传入 (评估工厂模式下预设 ScoreEffect
  的需求场景，暂时不抉择，代码保持仅接受类型声明)
- [ ] **T-017**: 深入评估与设计守卫（Guard）与规则多态架构 (由用户 2026-06-16 确认挂起)
- [ ] **T-004**: 迁移已有硬编码条件到正交体系 (将已有的 max_cost、race_whitelist 等迁移为正交组件)(批注:
  同时共存,需要进一步确认有没有必要)
- [ ] **T-005**: AI 生成 MCP Schema 支持 (暴露正交组件元数据，用于 MCP 精确校验)(批注:理清前面内容先)

---

## 待评估问题 (Unloaded Context)

- [x] **Q-001, Q-002, Q-003, Q-007, Q-010**: 已解决并答复 (归档批次: 2026-06-16)
