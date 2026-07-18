# 任务归档 - ai-config-validation - 2026-07-17

> 归档日期: 2026-07-17
> 原位置: `architecture-context/ai-config-validation/TRACKER.md`

## 废弃与过时任务梳理（针对 P-1.x, P-2.x, P-3.x）

由于架构认知更新（静态属性配置归 `.cardgroup` 文件，动态规则归 `EvaluatorTree` 并直接绑定到目标组），之前制定的关于“规则/条件匹配单卡属性”的任务成为伪需求，因此将它们整段归档废弃。

### 原计划：目的 1：编码类 rule/condition 定位与真实内容
- [x] P-1.1 盘点现有编码类能力面 - ✅ (2026-07-09)
- [x] P-1.2 设计真实规则/条件集 - [废弃] 之前错误地混淆了静态权重和动态匹配。
- [x] P-1.3 决策落点（先讨论，不预设） - [废弃]
- [x] P-1.4 实现 + 验证（决策后实施） - [废弃]

### 原计划：目的 2：正交积木补齐
- [x] P-2.1 枚举缺失积木 - [归档/搁置]
- [x] P-2.2 分批实现 + 验证 - [归档/搁置]

### 原计划：目的 3：确认 AI 生成配置的能力边界
- [x] P-3.1, P-3.2, P-3.3 - [归档/搁置]

## 过时实战验证记录

### 实战验证记录（T-102，2026-07-08）

通过 `McpToolDriverTest` 直接调 `McpToolProvider.call`（绕过 JSON-RPC）跑通 19 个工具全链路，**无运行期崩溃**。暴露的真实缺口：

1. **正交组件缺少类型签名（最重要，归 T-103）**：`list_orthogonal_components` 未暴露输入/输出类型，AI 无法预判 `hand_cards`(
   输出 `List<Card>`) 直接接 `gte`(期望 `Int`) 类型不匹配，实跑报 `PIPELINE_TYPE_MISMATCH`。正确组合需
   `hand_cards → count_projection → gte`。
2. **`bindingIds` 与 `managerId` 语义混淆（归 T-103）**：GROUP 绑定要求 `managerId` 必填，且 `bindingIds` 必须是
   `save_card_group` 返回的绑定条目 ID（非 managerId）。AI 极易混淆，实跑报 `manager_id_missing` +
   `binding_group_not_found`。
3. **`contentJson` 是 String 型（归 T-103）**：`save_evaluator_tree_template` / `save_template` 需传 stringify 后的 JSON
   字符串，描述易被误读传嵌套对象，实跑报反序列化失败。
4. **`parse_hearthstone_deck_code` 次要歧义（可选）**：`heroes` 仅返回 dbfId（无职业名映射）；`totalCardsInCode` 为去重后种类数（本例
   17），易被误读为 30 张整卡。

### 实战验证：SOP 编组→建树（2026-07-09，DeckSopFullFlowTest）

用 `AAEBAZ8F...`（圣契圣骑士，17 张卡）走完整 SOP：
...
5 组各建评估树（typed_simple_rule(limit=3) + 正交 hand_cards≥1 守卫），全部 commit 成功。

### 缺口
- [x] G-01 `get_draft_status` 未暴露 MCP - ✅已闭环（T-101）
- [x] G-02 无删除工具（delete_evaluator_tree / delete_card_group） - [暂挂起] UI可删
- [x] G-03 `validate_leaf_config` 未独立暴露 - [待评估]
- [x] G-04 无工具查已有卡组分组的绑定条目 ID（bindingIds） - ✅已闭环（T-105）
- [x] G-05 `get_card_group_detail` 不返回 cost/type/attack/health - ✅已闭环 (2026-07-09)
- [x] G-06 无 cardId 集合匹配的编码规则（typed/list_simple_rule 只做费用匹配） - ✅已闭环（架构原生支持：树天然绑定到组，不走 rule 判断，同时静态权重归 config）
