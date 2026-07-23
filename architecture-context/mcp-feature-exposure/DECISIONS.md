# 架构决策记录 - mcp-feature-exposure

## 决策列表

### D-201: MCP 工具暴露设计原则：读写分离与高内聚合并

- **状态**: ✅ 已收敛
- **日期**: 2026-07-23
- **背景**:  
  随着领域功能的增加，如果每个 CRUD 都暴露独立 Tool，会导致 MCP 工具数量急剧膨胀（如 >25 个），造成 LLM 上下文选择困难与误调用。
- **决策内容**:
    1. 所有“查询类”工具优先采用单一 Action 驱动工具模式：如 `combo_plan` 工具通过 `action="LIST"` 与 `action="GET"`
       合并列表与详情查询。
    2. 所有“修改/写入类”工具使用强类型的专用 Tool（如 `save_combo_plan`、`save_purpose_tag_rule`），利用 Jackson JSON Schema
       自动推导必填校验，保障类型安全。
    3. 工具响应统一结构，任何校验失败或缺失参数返回 `isError=true` 及清晰的引导提示。

---

### D-202: Combo 战术编排与评估树的解耦关联设计

- **状态**: ✅ 已收敛
- **日期**: 2026-07-23
- **背景**:  
  Combo 战术编排（`ComboPlanDefinition`）描述多张卡牌打出的前后置依赖与时序规则。过去 Combo 与评估树绑定关系较为模糊。
- **决策内容**:
    1. ComboPlan 保持为独立的战术序列领域模型，拥有独立的存储表与逻辑。
    2. 评估树节点通过在正交条件/规则中引用 ComboPlan ID，或者在 `EvaluatorTreeBinding` 中进行组合关联。
    3. MCP 暴露层提供 `combo_plan` 探查工具，AI 在设计评估树前可先检索当前生效的 Combo 方案，并在正交叶子中进行精确绑定。

---

### D-203: PurposeTag (用途标签) 动态规则驱动与 MCP 全闭环

- **状态**: ✅ 已收敛
- **日期**: 2026-07-23
- **背景**:  
  原 `DraftModels.kt` 中 `CreateDraftRequest` 的 `bindingType=PURPOSE_TAG` 标记为“暂无工具支持”。系统已将 PurposeTag
  改造成可配置意图规则（`PurposeTagIntentRule`），但 AI 无法通过 MCP 探查和操作。
- **决策内容**:
    1. 增加 `purpose_tag` 工具暴露系统内已注册的标签定义及对应 `PurposeTagIntentRule`（包含
       stage、orderWeight、replan、priority 等参数）。
    2. 扩展 `AiDraftTreeToolProvider`，全面支持 `bindingType=PURPOSE_TAG` 并在校验层进行标签合法性检查。
