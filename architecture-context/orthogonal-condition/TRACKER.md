# 任务追踪 - orthogonal-condition

> 已归档: 2026-06-12, 路径: `archive/2026-06-12/SUMMARY.md`

## 当前目标

设计并实现正交化的条件决策系统，解耦数据提取与比较算子，并重构配置 UI 与 AI 生成元数据支撑体系。

## 任务列表

| 编号    | 状态       | 任务                                                                   | 说明                                                                                                                                                              |
|-------|----------|----------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| T-001 | archived | 创建正交组合条件最小模型                                                         | 定义 `DataSource`、`Operator` 及 `ConditionAssembler` 的代码骨架并单测通过                                                                                                    |
| T-002 | ✅ 已完成    | 评估树/权重计算中复用数据源                                                       | 重构策略树叶子节点的权重计算，允许其引用 `DataSource` 动态乘法计分                                                                                                                        |
| T-003 | pending  | 接入配置端 UI (configUi) 动态渲染                                             | 在条件属性面板中，复用 `DynamicFieldForm` 并根据选中算子渲染表单                                                                                                                      |
| T-004 | pending  | 迁移已有硬编码条件到正交体系                                                       | 将已有的 max_cost、race_whitelist 等硬编码原子条件迁移为正交组件实现                                                                                                                  |
| T-005 | pending  | AI 生成 MCP Schema 支持                                                  | 暴露出正交组件的元数据，供 MCP 生成时精确校验，防止模型幻觉                                                                                                                                |
| T-006 | 🔄 进行中   | 规则（Rule）正交化重构（方案二）                                                   | 分阶段推进。✅ ScoreEffect 最小骨架完成；✅ rule-x-score-effect-declaration (编码 Rule 声明 ScoreEffectType，默认 CONSTANT)；🟡 完整 `Rule = Guard + ScoreEffect + ControlAction` 仍待后续拆分 |
| T-007 | ✅ 已完成    | ScoreEffect 最小模型                                                     | 删除评估叶子的旧权重壳，引入 `lin.rule.score`、`ScoreEffect`、内置评分算子，并让条件叶子通过 Guard 命中后计分                                                                                       |
| T-008 | ✅ 已完成    | ScoreOperator 元数据接入 UI                                               | 拆分 EvaluatorLeafMeta 静态4字段 + 动态算子参数；通过 ScoreOperatorOptionProvider SPI 提供算子下拉；EvaluatorPropertyEditorStrategy 支持算子变更时动态重建表单，Branch 节点跳过评分效应字段                   |
| T-009 | pending  | ScoreOperator Provider 化评估                                           | 当前评分算子是内置注册表；当外部插件需要扩展评分算子时，抽取 `ScoreOperatorProvider` SPI                                                                                                      |
| T-010 | pending  | 硬编码 RULE 正交化                                                         | 当前 `RULE` 仍保持原 `RuleLogic` 输出；后续再拆成 Guard、ScoreEffect、ControlAction                                                                                             |
| T-011 | pending  | ARCH-UNSETTLED U-005: RuleBuilder scoreEffectType 是否直接接受 ScoreEffect | `scoreEffectType()` 目前只声明类型，是否应直接接受 ScoreEffect 对象以支持预设值 (见 RuleBuilder.kt:39)                                                                                  |
| T-012 | pending  | ARCH-UNSETTLED U-006: toGuardScoreRule 限制仅 ConstantScore             | `toGuardScoreRule` 服务于 CONDITION 叶子（固定 ConstantScore），不应包含 SourceScore 分支；条件类 rule 边界尚未清晰 (见 RuleTreeBinding.kt:187)                                            |

## 备注

- 2026-06-13 起，动态权重壳已收敛为 ScoreEffect：Boolean 条件只作为 Guard，评分由 `ScoreEffect` 负责。
- 普通 `CONDITION / CONDITION_TREE` 叶子未命中时返回 `Continue(0.0)`；Branch 控制位单独构建 `ConditionLogic`，不再通过
  `RuleResult` 判定真假。
- 当前 ScoreEffect 最小模型索引见 `2026-06-13-minimal-model.md`（含 06-15 三层模型迭代）。

## 待评估问题 (Unloaded Context)

- [ ] **Q-001**: `Operator` 的 `categories` 类型为无限制的 `Set<String>`，为了防止拼写错误或分类混乱，是否需要通过
  Enum、Sealed Class 限制分类，或在校验期强制校验？
- [ ] **Q-002**: 参数数据类（如 `GteParams`）是否应该使用 `value class` 以减少运行期内存分配与包装开销？
- [ ] **Q-003**: 类似于 `ContainsRaceParams` 这种单字段的包装类，是否真的有额外包装的必要性，还是可以直接使用基础类型？是否需要用密封类/接口来限制所有的
  Parameter 类型？
- [x] **Q-004**: 统一静态验证机制（`ValidationResult` / `ValidationError`）如何做到通用化，以便能够无缝复用到项目其他模块（如策略树校验、配置项有效性校验等）中？
  *(已通过重构改名 RuleFieldSpec 为通用的 FieldSpec，并在 SpecValidator 中解耦了运行时异常抛出)*
- [x] **Q-005**: `ConditionRegistry` 采用自适应特征路由（判断参数中是否包含 `_sourceId` 与 `_operatorId`）代替 `dynamic_`
  魔术前缀时，是否存在参数键命名冲突边界风险？后续是否需要推进多态 AST Payload（方案二）？ *(已通过 D-009 决策：采用显式多态
  AST Payload 模型 OrthogonalRef 彻底解决)*
- [x] **Q-006**: 在 `ConditionAssembler` (动态条件拼装) 与 `RuleTreeBinding` (评估树节点解析绑定) 中都调用
  `SpecValidator` 重复校验参数，是否防御过度？引擎作为运行时，其与配置端（UI / MCP）的校验职责边界该如何清晰界定？ *(已在
  D-008 中界定：保留引擎内防御校验以保障运行时 Fail-Fast 的高可靠性)*
- [ ] **Q-007**: `ScoreOperator` 是否需要 SPI 化？当前通过 `DefaultScoreOperators` 内置注册表提供，待外部插件扩展需求出现后再确认。
- [x] **Q-008**: UI 是否应从 `ScoreOperator.paramSpecs` 动态渲染评分算子参数？当前 `factor/offset/pivot` 作为扁平字段临时承载。
  *(已通过 T-008 实现：动态加载 `DefaultScoreOperators[operatorId].paramSpecs`，算子变更时重建表单)*
- [ ] **Q-009**: `RULE` 是否逐步拆为 `Guard + ScoreEffect + ControlAction`？当前硬编码 Rule 保持原 `RuleLogic`，避免本轮扩大改动面。
- [ ] **Q-010**: `SourceScore` 参数反序列化是否需要独立 ObjectMapper / 测试矩阵？当前复用 `mapToRuleArgs`。
- [ ] **Q-011** (U-005): `RuleBuilder.scoreEffectType()` 目前只声明类型，是否应直接接受 `ScoreEffect` 对象以支持预设值？当前工厂模式下
  `leafConfig.scoreEffect` 由框架填充，builder 预设值是否有场景？
- [ ] **Q-012** (U-006): `toGuardScoreRule` 中 `missValue` 分支包含 `SourceScore`，但该函数服务于 CONDITION 叶子（固定
  ConstantScore），`SourceScore` 不该在此。是否应限制为仅 `ConstantScore` 分支？
