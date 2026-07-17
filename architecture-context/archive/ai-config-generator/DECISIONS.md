# 决策记录 - ai-config-generator

## D-001: 选择 Java MCP SDK 作为 MCP 协议层

- 背景: 项目是 Maven + Kotlin + Jackson + Koin + JavaFX，Kotlin MCP SDK 会额外引入 Ktor/kotlinx.serialization/KMP 解析复杂度。
- 选项: Kotlin MCP SDK；Java MCP SDK。
- 决定: ✅ 选择 Java MCP SDK，依赖为 `io.modelcontextprotocol.sdk:mcp-core` +
  `io.modelcontextprotocol.sdk:mcp-json-jackson2`，当前版本 `1.1.3`。
- 理由: Java SDK 与现有 Maven/Jackson 普通 JVM 入口更贴合，MCP 层只做协议适配，业务仍可继续写 Kotlin。
- 影响: MCP 入口放在 `configUi`，后续新增 Java SDK 依赖和 stdio server 适配。
- 日期: 2026-06-09

## D-002: MCP 放在 configUi 模块的独立入口

- 背景: MCP 需要读写配置 SQLite、查询 UI 侧配置服务和元数据目录。
- 选项: 新模块；`WeightHanderStrategy` 入口；`configUi` 独立入口。
- 决定: ✅ 放在 `configUi` 模块下，入口为 `lin.mcp.McpServerMain`。
- 理由: `configUi` 已拥有配置数据库、Jackson mapper、Koin 组装和配置服务，不应让引擎层承担作者侧流程。
- 影响: MCP 进程复用 `ModelsDefine().loadModules()`，后续需要为 exec/shade 增加独立 main 配置。
- 日期: 2026-06-09

## D-003: 验证规则按运行契约和作者侧体验分层

- 背景: 评估树最终由引擎启动绑定解释，但 AI/UI 还需要名称、描述、schema、保存体验等作者侧校验。
- 选项: 全部放引擎层；全部放 configUi/MCP；分层。
- 决定: ✅ 分层。运行契约校验已落地到 `WeightHanderStrategy` 的 `SpecValidator`（`FieldSpec` 类型/必填/约束校验），作者侧保存/编辑/AI
  schema 校验留在 `configUi/MCP`。
- 理由: 引擎只应关心配置能否解释执行，不应承接 AI 协作、UI 展示和 SQLite 作者侧规则。`SpecValidator`
  覆盖了引擎侧的契约合规校验，作者侧仅需做名称、描述、schema 等体验层校验。
- 影响: `DefaultAiConfigGenerationService` 做作者侧结构校验，`SpecValidator` 做引擎侧运行时契约校验。U-001 中 validator
  放置问题已确认，剩余子问题（依赖哪些 registry/provider）留待 T-009 处理。
- 日期: 2026-06-09（评估）/ 2026-06-23（🔶→✅ 确认）

## D-004: AI 生成条件逻辑已由正交条件底座接管

- 背景: 条件逻辑生成曾担心会引入条件引用、参数作用域、条件树复用和更复杂校验。
- 选项: 同步纳入 MCP 最小模型；只记录方向。
- 决定: ✅ 正交条件已通过 `orthogonal-condition` 主题完成（T-004 done），条件逻辑本身已可配置化。AI 生成侧不再需要"
  生成条件逻辑"，而是利用已有正交组件元数据（DataSource/Operator）提供精确校验。
- 理由: `orthogonal-condition` 建立了 DataSource→Operator→ConditionAssembler 的完整正交链路，AI
  配置生成只需暴露正交组件元数据供校验，无需重复建造条件引擎。
- 影响: T-004 已 done，`TRACKER.md` 中不再阻塞条件域扩张。后续 MCP 校验工作聚焦于 T-007（暴露正交组件元数据）。
- 日期: 2026-06-09（原始）/ 2026-06-23（更新）

## D-005: 编码类型不增设分类体系

- 背景: 叶子类型中只有正交类型有 `OrthogonalCategoryCatalog`
  分类（HAND/BOARD/HERO/GAME_STATE/MANA），编码类型（Plain/Coded）无分类。曾讨论是否统一加分类。
- 选项: 编码类型也加分类；不加分类。
- 决定: ✅ 不加分类。`list_evaluator_leaf_kinds` 继续全量返回。
- 理由:
  1. 旧体系 27 个规则大部分能正交化（这是重构目的），编码类未来是稀有物种，不构成分类需求。
  2. 正交类型有分类是因为组件是组合型的（DataSource+Transform+Operator），分类帮助理解维度语义；编码类型是原子型的，
     `sourceId` 本身即唯一标识。
  3. 加分类只增加查询步骤，无实际收益。
- 影响: T-013（原叶子分类说明任务）删除。`list_evaluator_leaf_kinds` 不变。
- 日期: 2026-06-30

## D-006: AI 生成内容不自动存模板，默认写正式配置

- 背景: 讨论 AI 生成的配置是否自动沉淀为模板。
- 选项: 全部自动存模板；不自动存；智能判断后存。
- 决定: ✅ 不自动存。AI 生成内容默认写入 `tree_config`（正式表）。模板沉淀由用户判断后手动提升。
- 理由:
  1. 自动存会导致模板池膨胀，一次性配置污染模板库。
  2. 模板应保持"精选"语义，AI 生成的是实例，不是模式。
  3. "智能提升"需开放 AI 写模板权限且判断标准难定，留待后续版本。
- 影响: V1 阶段模板对 AI 只读。后续可考虑"生成时复用已有模板"（只读匹配），而非"生成后提升"。
- 日期: 2026-06-30

## D-007: 所有模板统一只含结构不含参数

- 背景: 正交条件/规则的复用粒度比整棵评估树小，值得存模板。讨论模板存到哪一层、是否含参数。
- 核心原则: **模板是结构复用，不是配置复制。** 正式配置提升为模板时，必须剥离参数只留结构。
- 选项: 只存管道；存含算子完整模板；存含参数完整模板；统一只存结构。
- 决定: ✅ 所有模板（正交模板、评估树模板）统一只含结构，不含参数。
  - **正交模板结构**：DataSource + Transform + Operator/ScoreOp **类型**（引用哪些组件），不含组件参数。
  - **评估树模板结构**：树骨架（LogicNode 拓扑 + 叶子 Kind/sourceId 引用），不含叶子 args。
  - **正式配置**：完整含参数，写入 `tree_config`。从正式配置提升为模板时剥离参数。
- 理由:
  1. 模板语义是"可复用的结构模式"，参数是实例化差异点（阈值、系数），存参数即存变体，污染模板池。
  2. 不分层——统一原则更简单，避免"管道模板/完整模板"两种填写规范的复杂度。
  3. "换个算子就好了"说明算子是语义差异点，但算子类型仍属结构（用 gte 还是 linear 是结构选择），参数才是实例差异。
- 影响: 现有代码可能未处理参数剥离（细节问题，当前可用故未处理），新增 T-015 修复。T-014 模板查询返回纯结构。
  `orthogonal_templates.content_json` / 评估树模板 configData 表结构不变，靠序列化时剥离参数。
- 日期: 2026-06-30

## D-008: 模板智能沉淀与复用均交给 AI 判断

- 背景: 既然不自动存模板，讨论是否做"智能"沉淀。
- 核心认知: "智能"指 AI 大模型自身的判断能力，不是代码侧写判断逻辑。给 AI `save_template` 工具，AI 自己决定何时沉淀；给 AI
  `list_templates` 工具，AI 自己决定何时复用。
- 选项: 生成时复用（只读）；生成后提升（AI 写）；两者都做；都不做留人工。
- 决定: ✅ V1 同时开放复用与沉淀，均由 AI 自主判断。
  - **复用**：AI 生成前查模板库（`list_templates`），命中则引用结构自行填参数，未命中则自行构造。
  - **沉淀**：AI 生成配置后，若判断有复用价值，调用 `save_template` 提升为模板（剥离参数，对应 D-007/T-015）。
  - 不自动存（D-006）：AI 不强制每次生成都存模板，由 AI 判断是否值得沉淀。
- 理由:
  1. 判断标准由 AI 大模型能力承担，不需要代码写规则。
  2. 复用与沉淀是模板生态的两面，V1 同时开放让 AI 自主形成闭环。
  3. 之前"判断标准难定"理由不成立——判断是 AI 的职责，不是代码的职责。
- 影响: T-014 增加 `save_template` MCP tool（写权限）。AI 生成流程：查模板→匹配/构造→生成→判断沉淀。T-015 剥离参数在
  save_template 时强制执行。
- 日期: 2026-06-30

## D-009: 删除 card_catalog，configUi 通过 ATTACH 挂载 hs_cards.db

**状态**: ✅ 已锁定

**背景**: card_catalog 兼顾"卡牌元数据缓存"和"用途系统导入记录"两个职责，导致 cardId+name 事实来源不一致（.cardgroup 的
name 单向同步到 card_catalog，删除 card_catalog 条目产生孤儿数据）。T-010（暴露卡池给 AI）需要明确的事实来源。

**决策**: 删除 card_catalog 桥梁表（代码不再引用，物理表保留），configUi 通过 HikariCP `connectionInitSql` 执行
`ATTACH DATABASE 'hs_cards.db' AS hs`，SQL 中直接 `JOIN hs.cards` 获取卡牌 name。

**理由**:

1. cardId 事实来源：`.cardgroup` 文件是配置场景的唯一事实来源
2. name 事实来源：`hs_cards.db` 是卡牌元数据的权威来源
3. card_catalog 的"导入记录"语义迁移到 `card_purpose.created_date`，语义更清晰（配置日期而非导入日期）
4. ATTACH 方式性能等同单库，无需引入新的连接管理

**影响范围**:

- `CardPurposeRepository`：拆分配置/视图两套查询路径（配置模式主表 hs.cards，视图模式主表 card_purpose）
- `CardPurposeStore`：addCustomCard 签名从 (cardId, name) 改为 (cardId)，name 从 hs.cards 查
- `CardPurposeWorkbench`：单卡录入对话框去名称框；注入 `ActiveManagerHolder` 链接 Shell 顶部"当前卡组方案"选择

**日期**: 2026-07-01
