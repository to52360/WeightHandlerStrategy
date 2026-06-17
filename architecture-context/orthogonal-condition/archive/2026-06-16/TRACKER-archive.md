# 任务归档 - orthogonal-condition - 2026-06-16

> 归档日期: 2026-06-16
> 原位置: `architecture-context/orthogonal-condition/TRACKER.md`

## 阶段一：正交条件与 DataSource (已完成)

- [x] **T-001**: 创建正交组合条件最小模型 (定义 `DataSource`, `Operator`, `ConditionAssembler` 等)
- [x] **T-002**: 评估树/权重计算中复用数据源 (支持叶子节点引用 DataSource 动态乘法计分)

## 阶段二：评分效应 (ScoreEffect + ScoreOperator) 重构 (已完成任务)

- [x] **T-007**: ScoreEffect 最小模型实现 (Boolean 条件仅作为 Guard，算分由 ScoreEffect 负责)
- [x] **T-008**: ScoreOperator 元数据接入 UI (支持算子参数动态重建表单，Branch 节点跳过评分效应字段)

## 阶段三：硬编码迁移与 AI 配置适配 (已完成任务)

- [x] **T-003**: 接入配置端 UI (configUi) 动态渲染 (在条件属性面板中复用 DynamicFieldForm) *(已在 configUi 的
  OrthogonalConditionDialog 和 OrthogonalRuleDialog 中完整复用并接入了表单动态构建)*
- [x] **T-016**: 规则 (Rule) 行为正交化 (在评估树引入正交 Rule配置弹窗，只由 Guard + Score 两个维度组成，UI
  进行强类型关联匹配)

## 待评估问题 (已解答归档)

- [x] **Q-001**: `Operator` 的 `categories` 类型为无限制的 `Set<String>`，是否需要通过 Enum/Sealed Class 限制分类，或在校验期强制校验？
  *(已在 D-012.1 中规范为 OperatorCategories 常量，自由扩展)*
- [x] **Q-002**: 参数数据类（如 `GteParams`）是否应该使用 `value class` 以减少运行期内存分配与包装开销？ *(已在 D-012.2
  中明确禁用，因 value class 对泛型反射反序列化存在映射和类型签名隐患)*
- [x] **Q-003**: 类似单字段的包装类是否真的有必要，还是可以直接使用基础类型？是否需要用密封类/接口限制所有 Parameter 类型？
  *(已在 D-012.3 中明确单字段包装类对 UI 表单解析和向前兼容是必需的，禁止密封类过度限制)*
- [x] **Q-007**: `ScoreOperator` 是否需要 SPI 化？ *(已在 T-009 中完成)*
- [x] **Q-010**: `SourceScore` 参数反序列化是否需要独立 ObjectMapper / 测试矩阵？ *(已在 T-013 中通过单测验证)*
