# 最小可验证模型 - orthogonal-condition

> 已归档已收敛模型标记: 2026-06-12, 路径: `archive/2026-06-12/SUMMARY.md`

## 创建日期: 2026-06-10

## 骨架文件清单

| 文件                                                                                                                                                                                                 | 用途                  | 标记数                              |
|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------|----------------------------------|
| [DataSource.kt](file:///g:/liw_work/jiaoBen/Deck-Plugin-Market/WeightHanderStrategy/src/main/kotlin/lin/rule/condition/orthogonal/DataSource.kt)                                                   | 数据源提供者定义与示例         | U-001, P-001, P-002              |
| [Operator.kt](file:///g:/liw_work/jiaoBen/Deck-Plugin-Market/WeightHanderStrategy/src/main/kotlin/lin/rule/condition/orthogonal/Operator.kt)                                                       | 算子定义与运算示例           | U-002                            |
| [ConditionAssembler.kt](file:///g:/liw_work/jiaoBen/Deck-Plugin-Market/WeightHanderStrategy/src/main/kotlin/lin/rule/condition/orthogonal/ConditionAssembler.kt)                                   | 动态条件组装及类型校验         | U-003, P-003, U-001 (validation) |
| [ConditionRegistry.kt](file:///g:/liw_work/jiaoBen/Deck-Plugin-Market/WeightHanderStrategy/src/main/kotlin/lin/rule/condition/ConditionRegistry.kt)                                                | 拦截动态条件编译与传统条件并存     | 无 (已收敛)                          |
| [RuleTreeBinding.kt](file:///g:/liw_work/jiaoBen/Deck-Plugin-Market/WeightHanderStrategy/src/main/kotlin/lin/rule/handler/RuleTreeBinding.kt)                                                      | 评估树节点解析绑定与前置契约校验    | U-002 (validation)               |
| [SpecValidator.kt](file:///g:/liw_work/jiaoBen/Deck-Plugin-Market/WeightHanderStrategy/src/main/kotlin/lin/rule/parse/SpecValidator.kt)                                                            | 通用轻量参数静态约束校验器       | 无                                |
| [DataSourceProvider.kt](file:///g:/liw_work/jiaoBen/Deck-Plugin-Market/WeightHanderStrategy/src/main/kotlin/lin/serviceLoader/provider/DataSourceProvider.kt)                                      | 数据源 SPI 抽象接口        | 无                                |
| [OperatorProvider.kt](file:///g:/liw_work/jiaoBen/Deck-Plugin-Market/WeightHanderStrategy/src/main/kotlin/lin/serviceLoader/provider/OperatorProvider.kt)                                          | 比较算子 SPI 抽象接口       | 无                                |
| [DefaultOrthogonalComponentsProvider.kt](file:///g:/liw_work/jiaoBen/Deck-Plugin-Market/WeightHanderStrategy/src/main/kotlin/lin/rule/condition/orthogonal/DefaultOrthogonalComponentsProvider.kt) | 内置数据源和算子的 SPI 默认提供者 | 无                                |
| [FieldParser.kt](file:///g:/liw_work/jiaoBen/Deck-Plugin-Market/WeightHanderStrategy/src/main/kotlin/lin/rule/parse/FieldParser.kt)                                                                | 核心反射解析元数据规格工具       | 无                                |

## 方向总览

整体方向围绕“解耦数据提取与逻辑算子”展开：

1. **DataSource** 纯粹无状态地根据战局 `RuleEnv` 和上下文 `RuleContext` 抽取所需数值（如手牌数、法力、种族）。
2. **Operator** 纯粹进行数值/集合间的比较运算（如大于等于、包含等），并携带声明式 `FieldSpec` 契约约束。
3. **ConditionAssembler** 将 (DataSource + Operator + Args) 拼装为通用的 `ConditionLogic`（Kotlin 闭包），并在装配时做前置契约合法性校验。
4. **ConditionRegistry** 通过对 args 参数字典进行特征自适应路由，发现具有数据源和算子特征参数时，自动路由给
   `ConditionAssembler` 进行动态拼装，从而完全兼容了手写条件简单模式。

## 标记状态汇总

| 编号    | 主题                   | 文件                                                                                                                                                                   | 描述                                 | 状态            |
|-------|----------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------|---------------|
| U-001 | orthogonal-condition | [DataSource.kt](file:///g:/liw_work/jiaoBen/Deck-Plugin-Market/WeightHanderStrategy/src/main/kotlin/lin/rule/condition/orthogonal/DataSource.kt#L10)                 | resolve性能开销是否需缓存                   | 🔶 待验证        |
| U-002 | orthogonal-condition | [Operator.kt](file:///g:/liw_work/jiaoBen/Deck-Plugin-Market/WeightHanderStrategy/src/main/kotlin/lin/rule/condition/orthogonal/Operator.kt#L15)                     | Jackson参数在序列化时类型匹配规范               | 🔶 待验证        |
| U-003 | orthogonal-condition | [ConditionAssembler.kt](file:///g:/liw_work/jiaoBen/Deck-Plugin-Market/WeightHanderStrategy/src/main/kotlin/lin/rule/condition/orthogonal/ConditionAssembler.kt#L43) | 编译期做强类型兼容性校验                       | 🔶 待验证        |
| P-001 | orthogonal-condition | [DataSource.kt](file:///g:/liw_work/jiaoBen/Deck-Plugin-Market/WeightHanderStrategy/src/main/kotlin/lin/rule/condition/orthogonal/DataSource.kt#L52)                 | 手牌数量暂时硬编码                          | ⚠️ 可接受        |
| P-002 | orthogonal-condition | [DataSource.kt](file:///g:/liw_work/jiaoBen/Deck-Plugin-Market/WeightHanderStrategy/src/main/kotlin/lin/rule/condition/orthogonal/DataSource.kt#L63)                 | 战场龙族暂时硬编码                          | ⚠️ 可接受        |
| P-003 | orthogonal-condition | [ConditionAssembler.kt](file:///g:/liw_work/jiaoBen/Deck-Plugin-Market/WeightHanderStrategy/src/main/kotlin/lin/rule/condition/orthogonal/ConditionAssembler.kt#L43) | 强类型匹配校验逻辑待支持 assignment-compatible | ⚠️ 可接受        |
| P-004 | orthogonal-condition | [ConditionRegistry.kt](file:///g:/liw_work/jiaoBen/Deck-Plugin-Market/WeightHanderStrategy/src/main/kotlin/lin/rule/condition/ConditionRegistry.kt#L91)              | 拦截 dynamic_ 前缀 of 动态条件编译           | ✅ 已归档 (D-009) |
| U-001 | validation           | [ConditionAssembler.kt](file:///g:/liw_work/jiaoBen/Deck-Plugin-Market/WeightHanderStrategy/src/main/kotlin/lin/rule/condition/orthogonal/ConditionAssembler.kt#L48) | 引擎在此处做参数防御性校验，是否存在重复校验             | 🔶 待验证        |
| U-002 | validation           | [RuleTreeBinding.kt](file:///g:/liw_work/jiaoBen/Deck-Plugin-Market/WeightHanderStrategy/src/main/kotlin/lin/rule/handler/RuleTreeBinding.kt#L68)                    | 绑定时做前置校验，是否存在防御过度                  | 🔶 待验证        |
| U-004 | orthogonal-condition | [ConditionRegistry.kt](file:///g:/liw_work/jiaoBen/Deck-Plugin-Market/WeightHanderStrategy/src/main/kotlin/lin/rule/condition/ConditionRegistry.kt#L95)              | 特征自适应路由是否存在键命名冲突风险                 | ✅ 已归档 (D-009) |

>
任务化处理见 [TRACKER.md](file:///g:/liw_work/jiaoBen/Deck-Plugin-Market/architecture-context/orthogonal-condition/TRACKER.md)
