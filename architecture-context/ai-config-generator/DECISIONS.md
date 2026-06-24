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



