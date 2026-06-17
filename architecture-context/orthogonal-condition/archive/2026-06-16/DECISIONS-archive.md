# 决策归档 - orthogonal-condition - 2026-06-16

> 归档日期: 2026-06-16
> 原位置: `architecture-context/orthogonal-condition/DECISIONS.md`

## D-008: 引擎防御性校验与职责边界的权衡决策

- 背景: 在 `ConditionAssembler` (动态条件装配) 与 `RuleTreeBinding` (评估树节点解析绑定) 中均前置调用了 `SpecValidator`
  对条件或规则的参数进行防御性静态契约校验，这导致了可能存在重复验证和边界职责混淆的疑虑。
- 选项:
    - 选项一：在引擎层彻底移除前置校验，完全信任上游配置端（UI / AI）的合法性验证，以降低代码复杂度和运行时开销。
    - 选项二：保留引擎层在装配与绑定期的防御性校验（作为 Fail-Fast 拦截机制），并借助 `ARCH-UNSETTLED`
      标记隔离，待系统整体稳定及上游校验方案定型后再做精简评估。
- 决定: ✅ 选项二。
- 理由: 引擎作为整个决策插件的运行载体，必须具备独立防范并拦截脏数据的兜底能力，以防止格式错误的配置数据导致运行期偶发且难定位的空指针异常（NPE）或类转型异常。双重校验能够保证任何环境、任何网关配置失效时引擎仍可保持
  Fail-Fast 安全行为。
- 影响: 在 `ConditionAssembler.kt` 与 `RuleTreeBinding.kt` 相应入口保留参数校验并记录错误日志抛出异常，同时配套声明
  `U-001` 与 `U-002` 的 `validation` 待评估标记。
- 日期: 2026-06-12

## D-009: 放弃特征路由自适应，拥抱多态多维强类型 ConditionPayload

- 背景: 先前 D-007 采用了特征自适应路由，但这会在 Map 参数中混入魔法键（如 `_sourceId` / `_operatorId`），存在潜在的参数名冲突风险，且在大模型
  MCP Schema 表达及 UI 处理多态对象时容易产生逻辑混淆。
- 选项:
    - 选项一：继续保留特征自适应路由
    - 选项二：放弃特征路由，采用强类型多态 Payload 模型，新增 `ConditionPayload.OrthogonalRef`
- 决定: ✅ 选项二。
- 理由: 1. 彻底规避命名空间冲突；2. 在 AST 模型层层面对传统单体条件 `ConditionRef` 与正交条件 `OrthogonalRef`
  进行清晰的物理与逻辑分类；3. 提供更强健的强类型编译器支持，使反序列化与前端处理更加直观。
- 影响: 重构了 `ConditionNode.kt`、`ConditionRegistry.kt`、`ConditionAssembler.kt`、`RuleTreeBinding.kt`、
  `ConditionTreeConfigService.kt`（注册 Jackson Mixin 反序列化），并适配了配置端的列表字段前缀获取及头部防御显示。
- 日期: 2026-06-12

## D-010: 规则树与评估树职责分离，并规划“规则（Rule）正交化”（方案二）

- 背景: 在 T-002 执行过程中，发现评估树叶子节点同时承载了算分（Scoring）与控制动作（modifyCard）的双重职责，且条件作为叶子时被强行算分，违背了关注点分离原则。
- 选项:
    - 选项一：保持现状，在叶子包装层同时处理算分和控制
    - 选项二：推行规则正交化（Rule = 守卫条件 + 评分效应 + 控制动作），彻底将 Boolean 条件从算分职责中剥离，仅作为守卫条件（Guard）
- 决定: ✅ 选项二。
- 理由: 1. 概念极度纯净，条件树退回纯 Boolean 判定；2. 算分与控制完全解耦，能够按需自由组合；3. 未来可彻底消灭硬编码 Rule
  类，实现 100% 配置驱动。
- 影响: 目前该方案决定采纳，但由于影响较大，暂不实施，留待后续深入研究。未来将在 TRACKER.md 中添加重构任务。
- 日期: 2026-06-12

## D-011: ScoreEffect 三层叶子模型与 builtInFields 声明式生成

- 背景: 重构前所有叶子共享 `EVALUATOR_LEAF_BUILT_IN_FIELDS` 全局硬编码（scoreEffectType + constantScore + scoreSourceId +
  scoreOperatorId），CONDITION 叶子不需要评分类型选择，RULE 叶子却无法声明自己的评分策略。
- 选项:
    - 选项一：保持全局单一 `EVALUATOR_LEAF_BUILT_IN_FIELDS`，在各处 if-else 区分
    - 选项二：三层模型 + 声明式 builtInFields 生成
- 决定: ✅ 选项二。三层模型：
    1. **CONDITION / CONDITION_TREE** → 固定 `CONDITION_BUILT_IN_FIELDS` = [constantScore, missValue]，ConstantScore 不可选
    2. **编码 Rule** → 通过 `RuleRegistration.scoreEffectType` 声明（CONSTANT 默认 / SOURCE / NONE），框架按
       `scoreEffectFieldsFor()` 动态生成 builtInFields
    3. **配置型 Rule**（后续）→ 弹窗现编全量 ScoreEffect
- 理由: 1. 消除全局硬编码，builtInFields 按声明动态生成；2. CONDITION 表单更简洁（无评分类型选择器）；3. 编码 Rule 默认
  CONSTANT 接上被重构切断的线，factory 通过 `leafConfig.scoreEffect` 直接拿解析好的值；4. ScoreEffectType 枚举与
  EvaluatorLeafSourceType 平行，各司其职
- 影响: 重构了 `EvaluatorLeafMeta.kt`（新增 `CONDITION_BUILT_IN_FIELDS` + `scoreEffectFieldsFor()`）、`RuleBean.kt`（新增
  `ScoreEffectType` 枚举）、`RuleBuilder.kt`（新增 `scoreEffectType()` 方法）、`RuleRegistry.kt`（`leafMetas()` 按声明生成
  builtInFields）、`ConditionRegistry.kt`（使用 `CONDITION_BUILT_IN_FIELDS`）、`RuleTreeBinding.kt`（`toGuardScoreRule` 读取
  `missValue`）
- 日期: 2026-06-15

## D-012: Operator 与 评分效应参数的强类型约束规范

- 状态: ✅ 已锁定
- 背景: 随着正交化条件的推进，我们需要厘清算子分类 categories 的规范，并就参数类型（是否要用 value class
  优化内存、是否要保留单字段包装类、是否要引入密封类限制）做出明确抉择，防范未来的过度设计。
- 决策:
    1. **分类规范 (Q-001)**：保持 `Set<String>` 开放类型以利于插件自由定义新分类，但在 `Operator.kt` 建立
       `OperatorCategories` 预设分类常量，统一 UI 过滤维度并防范拼写错误。
    2. **禁用 value class (Q-002)**：为防止泛型参数反序列化 `mapToRuleArgs` (Jackson) 在解包多态情况下发生类型擦除、映射奔溃或反射签名异常，
       **严禁**使用 `value class` 作为条件或评分算子的参数类型，统一使用 `data class`。
    3. **保留单字段包装类 (Q-003)**：单字段包装类（如 `GteParams`）是必需的。它不仅提供了属性字段规格反射推导的
       propertyName，还保护了未来算子追加新参数时的前向兼容性。不使用任何密封类来强行限制参数类型，以保留 POJO
       与第三方扩展的最大灵活性。
- 影响范围: 影响了 `Operator.kt` 规范以及所有算子与评分效应的参数类设计。
- 日期: 2026-06-15

