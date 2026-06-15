# 归档摘要 - orthogonal-condition - 2026-06-12

## 归档说明

- 归档日期: 2026-06-12
- 归档范围: 归档已完成任务 T-001 (正交模型骨架)、T-002 (动态权重与元数据重命名重构)，已确定决策 D-001 至
  D-009，以及模型索引中已完全收敛的标记 P-004、U-004。
- 原文件位置: `architecture-context/orthogonal-condition/`

## 已完成任务

| ID    | 描述             | 结论                                                             |
|-------|----------------|----------------------------------------------------------------|
| T-001 | 创建正交组合条件最小模型   | 已实现 DataSource 与 Operator 物理/逻辑解耦，重构显式多态 AST 结构 OrthogonalRef。 |
| T-002 | 评估树/权重计算中复用数据源 | 已实现乘数数据源的动态权重计算，并在全工程将误导的 *UiItem 类名重命名为 *Meta 元数据系列。          |

>
细节: [TRACKER-archive.md](file:///g:/liw_work/jiaoBen/Deck-Plugin-Market/architecture-context/orthogonal-condition/archive/2026-06-12/TRACKER-archive.md)

## 已确定决策

| ID    | 简述                  | 决定                                                                  |
|-------|---------------------|---------------------------------------------------------------------|
| D-001 | 数据源与算子解耦            | 采用 Logic Tree -> Dynamic Assembler -> DataSource + Operator 三层解耦模型。 |
| D-002 | 数据源传参签名形式           | 将 RuleEnv 作为 context parameter，RuleContext 作为常规形参解决 receiver 匹配歧义。  |
| D-003 | DSL 形式定义 DataSource | 使用 inline 泛型 DSL 构建器配合 reified 反射，消除大量冗余类样板代码。                      |
| D-004 | 算子参数校验与 DSL 化       | 统一使用 Jackson 反序列化在装配期进行强类型参数转换与 Fail-Fast 校验。                       |
| D-005 | FieldSpec 统一自研校验    | 自研轻量纯 Kotlin SpecValidator 直接解析 FieldSpec，共享在引擎、UI 与 AI 校验端。        |
| D-006 | 正交 SPI 依赖反转         | 将提供者接口下沉在服务提供包中实现依赖反转，与内置具体实现彻底物理隔离。                                |
| D-007 | 特征自适应分发路由           | 采用魔法键自动分发以兼容存量（已被 D-009 决策推翻并废弃）。                                   |
| D-008 | 防御性校验边界职责           | 引擎保留装配与绑定前置静态校验以实现 Fail-Fast。                                       |
| D-009 | 强类型多态 Payload 模型    | 彻底废弃特征特征自适应，转而引入强类型 OrthogonalRef 作为第一等 AST 负载。                     |

>
细节: [DECISIONS-archive.md](file:///g:/liw_work/jiaoBen/Deck-Plugin-Market/architecture-context/orthogonal-condition/archive/2026-06-12/DECISIONS-archive.md)

## 已收敛模型

| 文件                   | 收敛状态  | 已解决标记        |
|----------------------|-------|--------------|
| ConditionRegistry.kt | ✅ 已收敛 | P-004, U-004 |

>
细节: [MODELS-archive.md](file:///g:/liw_work/jiaoBen/Deck-Plugin-Market/architecture-context/orthogonal-condition/archive/2026-06-12/MODELS-archive.md)

## 未收敛标记（仍活跃）

| 编号    | 文件:行号                                                                                                                                                                    | 描述                                          |
|-------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------|
| U-001 | [DataSource.kt#L10](file:///g:/liw_work/jiaoBen/Deck-Plugin-Market/WeightHanderStrategy/src/main/kotlin/lin/rule/condition/orthogonal/DataSource.kt#L10)                 | resolve 性能开销是否需要缓存及缓存设计                     |
| U-002 | [Operator.kt#L15](file:///g:/liw_work/jiaoBen/Deck-Plugin-Market/WeightHanderStrategy/src/main/kotlin/lin/rule/condition/orthogonal/Operator.kt#L15)                     | Jackson 参数反序列化的复杂泛型擦除匹配规范                   |
| U-003 | [ConditionAssembler.kt#L30](file:///g:/liw_work/jiaoBen/Deck-Plugin-Market/WeightHanderStrategy/src/main/kotlin/lin/rule/condition/orthogonal/ConditionAssembler.kt#L30) | 编译期强类型匹配校验逻辑有待优化（如支持 assignment-compatible） |
| P-001 | [DataSource.kt#L52](file:///g:/liw_work/jiaoBen/Deck-Plugin-Market/WeightHanderStrategy/src/main/kotlin/lin/rule/condition/orthogonal/DataSource.kt#L52)                 | 手牌数量数据源暂使用硬编码模拟数据                           |
| P-002 | [DataSource.kt#L63](file:///g:/liw_work/jiaoBen/Deck-Plugin-Market/WeightHanderStrategy/src/main/kotlin/lin/rule/condition/orthogonal/DataSource.kt#L63)                 | 战场龙族数据源暂使用硬编码模拟数据                           |
| P-003 | [ConditionAssembler.kt#L30](file:///g:/liw_work/jiaoBen/Deck-Plugin-Market/WeightHanderStrategy/src/main/kotlin/lin/rule/condition/orthogonal/ConditionAssembler.kt#L30) | 强类型契约匹配逻辑有待升级支持兼容匹配而非纯等值匹配                  |
| U-001 | [ConditionAssembler.kt#L41](file:///g:/liw_work/jiaoBen/Deck-Plugin-Market/WeightHanderStrategy/src/main/kotlin/lin/rule/condition/orthogonal/ConditionAssembler.kt#L41) | (validation) 引擎端与配置端可能存在的重复校验与性能损耗          |
| U-002 | [RuleTreeBinding.kt#L72](file:///g:/liw_work/jiaoBen/Deck-Plugin-Market/WeightHanderStrategy/src/main/kotlin/lin/rule/handler/RuleTreeBinding.kt#L72)                    | (validation) 绑定前置校验是否存在防御过度                 |

## 参考资料

不归档，原位置: `architecture-context/orthogonal-condition/references/`
