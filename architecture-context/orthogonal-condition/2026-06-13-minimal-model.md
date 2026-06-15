# 最小可验证模型 - orthogonal-condition / score-effect

## 迭代: 2026-06-13 → 2026-06-15 (三层模型 + 声明式 builtInFields)

## 骨架文件清单

| 文件                                                      | 用途                                                                                                              | 标记数                 |
|---------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------|---------------------|
| WeightHanderStrategy/.../score/ScoreEffect.kt           | ConstantScore/SourceScore 各加 `missValue: Double = 0.0`                                                          | 无                   |
| WeightHanderStrategy/.../score/ScoreOperator.kt         | `ScoreOperator<I,P>` 接口 + `scoreOperator{}` DSL 构建器                                                             | 无                   |
| WeightHanderStrategy/.../score/DefaultScoreOperators.kt | v1 内置三个算子：`identity` (直接作为分数) / `linear` (input*factor+offset) / `reverse_linear` ((pivot-input)*factor+offset) | U-001               |
| WeightHanderStrategy/.../tree/EvaluatorLeafMeta.kt      | 新增 `CONDITION_BUILT_IN_FIELDS` + `scoreEffectFieldsFor()`；消除 `EVALUATOR_LEAF_BUILT_IN_FIELDS` 全局硬编码             | 无                   |
| WeightHanderStrategy/.../tree/EvaluatorTreeInstance.kt  | Branch 控制位从 `RuleLogic` 改为 `ConditionLogic`                                                                     | 无                   |
| WeightHanderStrategy/.../build/RuleBean.kt              | `ScoreEffectType` 枚举 (CONSTANT/SOURCE/NONE)；`RuleRegistration.scoreEffectType` 默认 CONSTANT                      | 无                   |
| WeightHanderStrategy/.../build/RuleBuilder.kt           | 新增 `scoreEffectType()` 声明入口                                                                                     | U-005               |
| WeightHanderStrategy/.../registry/RuleRegistry.kt       | `leafMetas()` 按 `scoreEffectFieldsFor()` 生成 builtInFields；移除 `BUILT_IN_WEIGHT_PROPS`                            | 无                   |
| WeightHanderStrategy/.../condition/ConditionRegistry.kt | `leafMetas()` 使用 `CONDITION_BUILT_IN_FIELDS`                                                                    | 无                   |
| WeightHanderStrategy/.../handler/RuleTreeBinding.kt     | `toGuardScoreRule` 读取 `missValue`；GuardScoreRule 绑定、Branch 条件绑定、SourceScore 编译校验                                | U-002, U-003, U-006 |
| configUi/.../db/EvaluatorLeafSourceCatalog.kt           | CONDITION_TREE 使用 `CONDITION_BUILT_IN_FIELDS`                                                                   | 无                   |
| configUi/.../service/TreeConfigService.kt               | 评估树 JSON 注册 `ScoreEffect` 多态子类型                                                                                 | 无                   |
| configUi/.../ui/DynamicFieldForm.kt                     | 为评分效应类型与评分算子提供 v1 内置下拉选项                                                                                        | 无                   |

## 方向总览 (2026-06-13 初版)

本轮最小模型把"条件算分"收敛为 Guard + ScoreEffect：`CONDITION / CONDITION_TREE` 在评估树普通叶子中只负责得到
Boolean，命中时执行 `ScoreEffect` 产生分数，未命中时返回 `Continue(0.0)`。`ScoreEffect.SourceScore` 复用正交条件已有的
`DataSource`，再通过 `ScoreOperator` 将数据源输出转换为分数。Branch 控制位不再复用 `RuleLogic` 的 Continue/Prune 语义，而是直接绑定
`ConditionLogic`。

## 方向总览 (2026-06-15 迭代)

评分效应体系收敛为**三层叶子模型**：

1. **CONDITION / CONDITION_TREE** → 固定 ConstantScore，builtInFields = `[constantScore, missValue]`，无需评分类型选择
2. **编码 Rule** → 通过 `RuleRegistration.scoreEffectType` 声明评分类型（CONSTANT 默认 / SOURCE / NONE），框架按声明动态生成
   builtInFields
3. **配置型 Rule**（后续）→ 全量 ScoreEffect 弹窗现编

改动：`ScoreEffect` 加 `missValue`、消除全局硬编码 `EVALUATOR_LEAF_BUILT_IN_FIELDS`、`toGuardScoreRule` 读 missValue、RULE
默认 CONSTANT 接上被重构切断的线。

## 标记状态汇总

| 编号    | 文件:行号                        | 描述                                                                                           | 状态            |
|-------|------------------------------|----------------------------------------------------------------------------------------------|---------------|
| U-001 | DefaultScoreOperators.kt#L83 | 评分算子暂以内置注册表提供，是否升级为 SPI Provider 待评估                                                         | 🔶 待验证        |
| U-002 | RuleTreeBinding.kt#L233      | `SourceScore` 参数暂复用规则参数 ObjectMapper，反序列化边界待独立测试覆盖                                           | 🔶 待验证        |
| U-003 | RuleTreeBinding.kt#L81       | 硬编码 `RULE` 暂保持原 `RuleLogic` 输出，未拆成 Guard + ScoreEffect + Action                              | 🔶 待验证        |
| U-004 | EvaluatorLeafMeta.kt#L18     | UI 暂用扁平字段承载评分效应多态参数，后续应按 `scoreOperator` 动态切换表单                                              | ✅ 已解决 (T-008) |
| U-005 | RuleBuilder.kt#L39           | `scoreEffectType()` 目前只声明类型，是否应直接接受 ScoreEffect 对象以支持预设值                                     | 🔶 待验证        |
| U-006 | RuleTreeBinding.kt#L187      | `toGuardScoreRule` 不应包含 `SourceScore` 分支（CONDITION 叶子固定 ConstantScore）；应限制为仅 `ConstantScore` | 🔶 待验证        |

> 任务化处理见 TRACKER.md
