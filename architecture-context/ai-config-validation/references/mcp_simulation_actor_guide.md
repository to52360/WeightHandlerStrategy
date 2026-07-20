# MCP 模拟实战 Actor 角色行为规范与演练 SOP

本文档作为 AI 在 **实战演练/MCP 工具集成测试**场景下的 **角色边界约束与行为指南**，解决 AI
在演练中出现的“上帝视角越权绕过工具”或“过度陷入剧情台词输出无意义废话”的痛点。

---

## 一、 角色双重边界厘清

| 维度             | 模式 A：代码开发与重构助手 (Developer Mode)                                                    | 模式 B：策略配置生成 Actor (Config Generator Actor)                                                                                                                                                  |
|:-----------------|:-----------------------------------------------------------------------------------------------|:-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **触发场景**     | 编写 Kotlin 代码、重构引擎、编写 Maven 单元测试、修复 Bug、维护架构文档。                      | 用户指令：“开始实战”、“用 MCP 测试”、“模拟第 X 分组策略”、“生成卡牌评估树”。                                                                                                                         |
| **工具权限**     | 可使用系统级代码工具：`view_file`, `replace_file_content`, `write_to_file`, `run_command` 等。 | **【严格受限】**：**严禁使用任何系统级代码编辑工具或直接改 DB 工具去绕过工作！**                                                                                                                     |
| **允许的工具链** | 项目全量开发工具。                                                                             | **仅允许使用 MCP 工具链**：`list_capability_background`, `card_pool`, `card_group`, `save_card_group`, `create_draft_tree`, `list_orthogonal_components`, `put_draft_leaf`, `commit_draft_tree` 等。 |
| **行为准则**     | 理性分析代码、提示重构风险、严谨完成构建校验。                                                 | 严格遵循 MCP 配置生成 4 步 SOP，不跳步、不越权、不伪造。                                                                                                                                             |
| **对话表达**     | 简洁专业的自然语言，配合 GitHub Markdown 呈现成果。                                            | **严禁无意义的角色戏精台词**。工具调用前简述业务意图，工具调用后直接渲染拓扑树图与诊断结果，提请人类确认。                                                                                           |

---

## 二、 模拟实战 Actor 模式的【三绝不】铁律

1. **【绝不上帝视角越权】**： 在 Actor 演练模式下， **严禁通过 `write_to_file` 修改代码或直接操纵数据库来“假装生成配置”**
   。策略配置必须 100% 经过 MCP 接口（草稿池 `put_draft_leaf` -> 引擎校验 -> `commit_draft_tree` 落盘）。
2. **【绝把人类当数据库】**： 在探查阶段， **严禁询问人类卡牌的效果、费用或类型**。AI 必须主动调用 `card_pool`
   (action="LIST"/"GET")、`list_card_group_sources` 或数据库探查，自行建立领域认知。
3. **【绝不清谈与无意义扮演】**： 严禁输出“好的主人，我现在扮演小助手，嗨嗨嗨”等无意义角色台词。AI 的“角色扮演”体现在 **严格以受限
   MCP Assistant 身份合规使用 Tool**，而非话术扮演。

---

## 三、 实战演练 4 步渐进式 SOP

```
[1. 探查依赖]  ───>  [2. 领域分组]  ───>  [3. 骨架拓扑对齐]  ───>  [4. 叶子拼装与提交]
card_pool/           save_card_group        create_draft_tree        list_orthogonal_components
capability_bg        (需含 description)    (输出缩进文字树图)       put_draft_leaf / commit
```

### 步骤 1：探查与依赖供给

- 调用 `card_pool` (action="LIST"/"GET") 探查目标卡牌元数据。
- 调用 `list_capability_background` 获取真实存在的规则与条件能力。

### 步骤 2：卡牌领域分组建模

- 调用 `save_card_group` 建立/更新策略分组。
- **必须为每个分组编写详尽的 `description`**，说明该分组在卡组中的战术定位与联动动机（禁止填充空值）。

### 步骤 3：草稿树骨架生成与拓扑对齐

- 调用 `create_draft_tree` 初始化草稿树骨架。
- **必须手工渲染「缩进文字树状图」** 呈现 AND/OR/LEAF 结构，暂停等待人类确认拓扑方向， **绝对禁止直接倒入原始大 JSON**。

### 步骤 4：正交管道拼装与落盘提交

- 调用 `list_orthogonal_components`（可传入 `targetOutputType` 或 `targetInputType` 按类型降噪查询），参考
  `type_compatibility_map` 确定管道流向。
- 调用 `put_draft_leaf` 填充正交条件（`ORTHOGONAL_CONDITION`）与正交打分（`ORTHOGONAL_RULE`）。
- 提交 `commit_draft_tree`，引擎校验通过后完成数据库落盘。

---

## 四、 极简自查 CheckList（每次实战前自动比对）

- [ ] 是否试图调用 `write_to_file` 或 `replace_file_content` 直接改代码/数据？如果是， **立刻停止，改用 MCP 工具**。
- [ ] 输出文本中是否有无意义的角色情绪台词？如果有， **删除废话，直接陈述业务意图与数据**。
- [ ] 是否在没有与人类确认「缩进文字树状图」拓扑前直接调用了 commit？如果是， **暂停并展示树图**。
