# 决策归档 - orthogonal-condition - 2026-06-12

## 已确定决策

### D-001: 正交条件系统：将数据源与算子做物理与逻辑解耦

- **决定**: 采用三层解耦模型：`Logic Tree` -> `Dynamic Assembler` -> `DataSource + Operator`
  。将数据抽取与算子比较的笛卡尔积组合从 $M \times N$ 类降为 $M + N$ 类。
- **日期**: 2026-06-10

### D-002: 数据源在解析时对 RuleEnv 与 RuleContext 的传参形式

- **决定**: `RuleEnv` 声明为 context parameter，将 `RuleContext` 作为常规方法形参，即
  `context(env: RuleEnv) fun resolve(context: RuleContext): T`，以避免 receiver 冲突。
- **日期**: 2026-06-10

### D-003: 采用 DSL 构建器形式定义 DataSource 实例

- **决定**: 使用 inline 泛型 DSL 构建器 `dataSource(...) { ... }` 动态创建匿名对象，并利用 `reified`
  自动获取反射元数据，消除大量面向对象声明模板。
- **日期**: 2026-06-10

### D-004: 算子参数集中校验与 Operator DSL 化

- **决定**: 声明强类型参数结构 `P`，利用 Jackson 在 `ConditionAssembler` 编译装配期统一做参数反序列化转换与 Fail-Fast 校验。
- **日期**: 2026-06-11

### D-005: 基于 FieldSpec 统一多端契约的轻量自研验证体系设计

- **决定**: 自研纯 Kotlin 且零运行依赖的轻量静态校验器 `SpecValidator`，直接解析通用的 `FieldSpec`（引擎、UI、AI 三端共享元数据）。
- **日期**: 2026-06-11

### D-006: 正交 SPI 接口依赖反转重构

- **决定**: 将 `DataSourceProvider` 与 `OperatorProvider` 等抽象 SPI 接口下沉到 `lin.serviceLoader.provider`
  中实现依赖反转，与具体的引擎内置实现解耦。
- **日期**: 2026-06-11

### D-007: 去魔术命名的正交自适应特征分发路由 (已被 D-009 推翻)

- **决定**: 曾决定采用基于 Map 的 `_sourceId` 和 `_operatorId` 自动特征路由以兼容老配置，后因潜在命名冲突在 D-009
  中被推翻，升级为显式多态 AST Payload。
- **日期**: 2026-06-11

### D-008: 引擎防御性校验与职责边界的权衡决策

- **决定**: 保留引擎层在装配与绑定期的防御性校验（作为 Fail-Fast 校验兜底），不将校验职责完全寄希望于上游，保障引擎高可靠性。
- **日期**: 2026-06-12

### D-009: 放弃特征路由自适应，拥抱多态多维强类型 ConditionPayload

- **决定**: 彻底放弃 `D-007` 的特征路由，引入强类型多态 AST 模型 `ConditionPayload.OrthogonalRef` 作为正交条件的第一等公民。
- **日期**: 2026-06-12

## 已归档的历史决策 (已折叠)

<details>
<summary>点击展开历史决策 D-001 至 D-007</summary>

### D-001: 正交条件系统：将数据源与算子做物理与逻辑解耦

- 背景: 当前条件系统（如 `HandCardCountCondition` 等）每个条件都是一个完整的逻辑体，难以应对数据源与算子笛卡尔积组合带来的类数量爆炸。
- 选项:
    - 选项一：继续原有的硬编码条件类设计
    - 选项二：三层解耦模型，即 `Logic Tree` -> `Dynamic Assembler` -> `DataSource + Operator`
- 决定: ✅ 选项二：三层解耦模型。
- 理由: 解耦后，每新增一个数据提取维度只需要添加一个 `DataSource`，新增一种比较手段只需添加一个 `Operator`
  。整体条件数量从 $M \times N$ 级降为 $M + N$ 级。
- 影响: 条件树无需做任何结构修改，运行时通过 `ConditionAssembler` 进行动态组装。
- 日期: 2026-06-10

### D-002: 数据源在解析时对 RuleEnv 与 RuleContext 的传参形式

- 背景: 规则引擎的上下文仅强制提供了 `RuleEnv` 的上下文参数（通过 `context(RuleEnv)`），而 `RuleContext` 在 `RuleLogic` /
  `ConditionLogic` 中作为 lambda 的 receiver（`RuleContext.()`）存在，并不稳妥绑定为 `context(...)` 参数。
- 选项:
    - 选项一：同时将 `RuleEnv` 和 `RuleContext` 声明为 context parameters：
      `context(env: RuleEnv, ctx: RuleContext) fun resolve(): T`
    - 选项二：将 `RuleEnv` 声明为 context parameter，将 `RuleContext` 作为常规方法形参：
      `context(env: RuleEnv) fun resolve(context: RuleContext): T`
- 决定: ✅ 选项二。
- 理由: 确保 `RuleContext` 能够在 lambda 内部通过 `this` 显式且安全地传递给数据源（如 `source.resolve(this)`），完美契合
  `RuleLogic` 和 `ConditionLogic` 的定义契约，避免编译器对 receiver 和 context parameter 进行匹配推导时出现类型匹配歧义。
- 影响: `DataSource.resolve` 方法签名设计为 `context(env: RuleEnv) fun resolve(context: RuleContext): T`，在
  `ConditionAssembler` 中统一通过 `source.resolve(this)` 显式传参进行调用。

### D-003: 采用 DSL 构建器形式定义 DataSource 实例

- 背景: 原有方案中每一个 `DataSource` 均需要声明为一个独立的 `object` 类，需要显式声明很多样板代码（如
  `outputType = Int::class`）。
- 选项:
    - 选项一：保留传统的 `object : DataSource<T>` 面向对象声明
    - 选项二：使用 inline 泛型 DSL 构建器 `dataSource(...) { ... }` 动态创建匿名对象
- 决定: ✅ 选项二。
- 理由: 1. 消除大量的样板文件与重复书写；2. 利用 `reified` 泛型让编译器自动获取并绑定 `outputType` 的反射元数据，增强类型安全性；3.
  依然天然兼容 Koin 注入与 ServiceLoader 注册。
- 影响: `DataSource.kt` 内只保留接口定义和 `dataSource` 构建器，所有的具体数据源示例直接用变量 `val` 定义。

### D-004: 算子参数集中校验与 Operator DSL 化

- 背景: 原本每个 `Operator` 的 `evaluate` 需要手动从 `Map<String, Any>` 中提参、转型（如 `args["threshold"] as? Number`
  ），缺乏集中参数类型校验与捕获机制，容易把配置拼写错误留到运行期。
- 选项:
    - 选项一：保留原始 `Map<String, Any>` 形参，由算子内部各自处理提参与强转
    - 选项二：声明强类型参数结构 `P`，利用 `objectMapper.convertValue` 在 `ConditionAssembler.assemble` 编译装配期统一集中解析与校验
- 决定: ✅ 选项二。
- 理由: 1. 彻底实现“一次解析，多次运行”，免除运行期每次解析 Map 的性能开销；2.
  将参数校验前置到编译装配期（Fail-fast），一旦配置缺失必填项或类型不匹配立即报错拒绝启动；3. 结合
  `operator(...) { input, params -> ... }` DSL，使算子逻辑纯净化，完全解耦参数处理。
- 影响: `Operator.kt` 升级为 `Operator<I, P>` 接口，支持 DSL 构建；`ConditionAssembler` 引入 Jackson 统一做转换校验。
- 日期: 2026-06-11

### D-005: 基于 FieldSpec 统一多端契约的轻量自研验证体系设计

- 背景: 为了在大模型生成、UI 配置表单拦截及决策引擎启动时统一对规则/条件的 args 进行约束校验，需要引入校验框架。若使用
  Hibernate Validator 等重型库，需要在注解和 FieldSpec 元数据中进行双重维护，包体与运行开销也较大。
- 选项:
    - 选项一：引入标准的反射校验库（Hibernate Validator + Java Bean 注解声明）
    - 选项二：自研纯 Kotlin 且零运行依赖的轻量静态校验器 `SpecValidator`，直接解析 FieldSpec（重构后的通用元数据格式）
- 决定: ✅ 选项二。
- 理由: 1. 避免双重维护，一处元数据声明在 AI、UI、引擎三端共享；2. `SpecValidator` 只进行契约校验并返回 `ValidationResult`
  ，绝不直接在核心底层抛出异常，满足多态消费（UI 提示 / AI 纠错 / 引擎启动 Crash）。
- 影响: 重命名 `RuleFieldSpec` 为 `FieldSpec` 以适配通用场景，实现 `SpecValidator.kt` 并通过 `SpecValidatorTest` 编写单元测试。
- 日期: 2026-06-11

### D-006: 正交 SPI 接口依赖反转重构

- 背景: `DataSourceProvider` 与 `OperatorProvider` 等 SPI 提供者接口和引擎内置组件具体实现以前全部混合存放在
  `lin.rule.condition.orthogonal.spi` 包内，包职责划分不纯净，不符合依赖反转原则。
- 选项:
    - 选项一：混合存放或整体移到通用服务加载包 `lin.serviceLoader.provider` 中
    - 选项二：将核心接口移出到 `lin.serviceLoader.provider` 中，而引擎的具体实现类（Default 提供者）留在
      `lin.rule.condition.orthogonal` 模块内
- 决定: ✅ 选项二。
- 理由: 典型的“依赖反转（Dependency Inversion）”原则体现：外层的纯 SPI
  服务层只保留接口抽象，具体的实现细节（内置提供者）下沉在模块内，既保障了接口包的极简度，又避免了循环依赖和包污染。
- 影响: 移动两个接口，内置实现复位并更新 `META-INF/services/` 配置文件映射。
- 日期: 2026-06-11

### D-007: 去魔术命名的正交自适应特征分发路由 (已被 D-009 推翻)

- 背景: 区分普通手写条件与动态正交拼接条件原先采用了魔术前缀匹配（即判断 `conditionId` 是否以 `"dynamic_"`
  开头），对配置端不太友好且扩展性弱。
- 选项:
    - 选项一：改写 AST 数据模型为多态 Payload（如新增 `OrthogonalRef` 类型），需要重构界面与 JSON 配置
    - 选项二：通过参数特征自适应路由，即当 args 里同时包含数据源识别键 `_sourceId` 与算子识别键 `_operatorId`
      时（且收敛两键为常量）即分发给正交装配器，并标注评估标记
- 决定: ✅ 选项二。
- 理由: 1. 零侵入，100% 兼容存量配置数据；2. 摆脱字符串魔术命名的限制，做到了纯粹基于特征的多态分发；3.
  便于后续评估冲突或在条件成熟后推进方案一。
- 影响: 更新 `ConditionRegistry.kt` 路由方法并引入 `KEY_SOURCE_ID` 常量。
- 日期: 2026-06-11

</details>
