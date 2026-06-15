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
- 决定: 🔶 分层。运行契约校验可考虑抽到 `WeightHanderStrategy`，作者侧保存/编辑/AI schema 校验留在 `configUi/MCP`。
- 理由: 引擎只应关心配置能否解释执行，不应承接 AI 协作、UI 展示和 SQLite 作者侧规则。
- 影响: 当前最小模型先在 `DefaultAiConfigGenerationService` 放作者侧结构校验，运行契约 validator 继续标记 U-001。
- 日期: 2026-06-09

## D-004: AI 生成条件逻辑暂只记录不展开

- 背景: 条件逻辑生成会引入条件引用、参数作用域、条件树复用和更复杂校验。
- 选项: 同步纳入 MCP 最小模型；只记录方向。
- 决定: ✅ 只记录方向，当前阶段不展开实现。
- 理由: 先让评估树 MCP 生成链路跑通，避免同时扩张两个配置域。
- 影响: `TRACKER.md` 中保留 T-004，后续单独进入条件逻辑生成讨论。
- 日期: 2026-06-09



