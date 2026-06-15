# 任务追踪 - orthogonal-condition

> 已归档: 2026-06-12, 路径: `archive/2026-06-12/SUMMARY.md`

## 整体进度

- **最后更新**: 2026-06-15
- **当前任务**: T-009 评分算子 SPI 化 & T-013 动态算分参数反序列化测试
- **整体状态**: 🔄 进行中

## 已锁定的关键决策 (来自 DECISIONS.md)

1. **D-008: 引擎防御性校验** - 保留引擎层在装配与绑定期的防御性校验（Fail-Fast 拦截机制），保障运行时高可靠性。
2. **D-009: 强类型多态 ConditionPayload** - 放弃特征自适应路由，采用多态多维 `ConditionPayload.OrthogonalRef` 彻底隔离参数并支持
   AST。
3. **D-010: 规则树与评估树职责分离** - 确立正交化大方向 `Rule = 守卫条件 + 评分效应`。
4. **D-011: ScoreEffect 三层叶子模型与声明式 builtInFields** - 区分 CONDITION（固定 ConstantScore）、编码 Rule（动态
   builtInFields）与配置型 Rule，简化 UI 并打通重构线。

---

## 任务列表

### 阶段一：正交条件与 DataSource (已完成)

- [x] **T-001**: 创建正交组合条件最小模型 (定义 `DataSource`, `Operator`, `ConditionAssembler` 等)
- [x] **T-002**: 评估树/权重计算中复用数据源 (支持叶子节点引用 DataSource 动态乘法计分)

### 阶段二：评分效应 (ScoreEffect + ScoreOperator) 重构 (进行中)

- [x] **T-007**: ScoreEffect 最小模型实现 (Boolean 条件仅作为 Guard，算分由 ScoreEffect 负责)
- [x] **T-008**: ScoreOperator 元数据接入 UI (支持算子参数动态重建表单，Branch 节点跳过评分效应字段)
- [ ] **T-009**: ScoreOperator Provider 化 (将内置评分算子注册表抽取为 `ScoreOperatorProvider` SPI)
- [ ] **T-013**: 完善 `SourceScore` 参数反序列化 (解决 U-002: 独立 ObjectMapper / 测试矩阵，保障运行时安全)

### 阶段三：硬编码迁移与 AI 配置适配 (待开始)

- [ ] **T-003**: 接入配置端 UI (configUi) 动态渲染 (在条件属性面板中复用 DynamicFieldForm)
- [ ] **T-004**: 迁移已有硬编码条件到正交体系 (将已有的 max_cost、race_whitelist 等迁移为正交组件)
- [ ] **T-005**: AI 生成 MCP Schema 支持 (暴露正交组件元数据，用于 MCP 精确校验)
- [ ] **T-016**: 规则 (Rule) 行为正交化 (在评估树引入正交 Rule配置弹窗，只由 Guard + Score 两个维度组成，UI
  进行强类型关联匹配)

---

## 挂起暂不处理任务 (由用户 2026-06-15 确认挂起)

- [ ] **T-014** (U-006): 限制 `toGuardScoreRule` 仅支持 `ConstantScore` 分支 (暂时无法抉择是否允许条件通过 SourceScore
  直接算分，代码暂不做改动，维持支持)
- [ ] **T-015** (U-005): 验证 `RuleBuilder.scoreEffectType` 是否支持对象传入 (评估工厂模式下预设 ScoreEffect
  的需求场景，暂时不抉择，代码保持仅接受类型声明)

---

## 待评估问题 (Unloaded Context)

- [ ] **Q-001**: `Operator` 的 `categories` 类型为无限制的 `Set<String>`，是否需要通过 Enum/Sealed Class 限制分类，或在校验期强制校验？
- [ ] **Q-002**: 参数数据类（如 `GteParams`）是否应该使用 `value class` 以减少运行期内存分配与包装开销？
- [ ] **Q-003**: 类似单字段的包装类是否真的有必要，还是可以直接使用基础类型？是否需要用密封类/接口限制所有 Parameter 类型？
- [ ] **Q-007**: `ScoreOperator` 是否需要 SPI 化？ (对应 T-009，即将完成)。
- [ ] **Q-010**: `SourceScore` 参数反序列化是否需要独立 ObjectMapper / 测试矩阵？ (对应 T-013，即将完成)。
