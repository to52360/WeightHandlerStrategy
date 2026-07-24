# 任务追踪 - mcp-feature-exposure (MCP 功能暴露总体规划)

> 决策记录见 [DECISIONS.md](./DECISIONS.md)  
> 领域模型与拓扑映射见 [MINIMAL-MODEL.md](./MINIMAL-MODEL.md)  
> 已知缺陷见 [KNOWN-DEFECTS.md](./KNOWN-DEFECTS.md)  
> 历史归档索引见 [ARCHIVE-INDEX.md](./ARCHIVE-INDEX.md)

## 当前目标

将 `configUi` 与 `WeightHandlerStrategy` 中已有的核心领域功能（Combo 战术编排、用途标签 PurposeTag
规则、配置生命周期与卡组代码解析等）全面暴露为标准的 MCP 工具链，使 AI 能够具备完整的全卡组策略配置生成、查询、修改与调试能力。

---

## 整体进度

- **当前任务**: 架构任务规划完成，等待开始阶段一实施
- **整体状态**: ⏸️ 规划已完成（暂不实施，待用户指令开启实施）

---

## 任务列表

### 阶段一：Combo 战术编排 MCP 工具链暴露 (Combo Plan MCP Exposure)

- [x] **T-201**: ComboPlan 领域元数据探查与 DTO 契约设计（梳理 `ComboPlanDefinition`、`ComboItem` 数据结构与 json 序列化）
- [x] **T-202**: `combo_plan` MCP 工具实现（支持 `action=LIST` 列出所有 Combo 方案摘要与 `action=GET` 获取指定 Combo
  依赖卡牌与步骤序列）
- [x] **T-203**: `save_combo_plan` 与 `delete_combo_plan` MCP 工具实现（支持 AI 动态创建、修补及删除 Combo 战术方案）
- [x] **T-204**: 评估树与 ComboPlan 绑定引用关系暴露（在评估树正交组件或绑定中暴露 Combo 引用探查接口）

### 阶段二：用途标签 (Purpose Tag) MCP 工具链暴露

- [ ] **T-211**: PurposeTag 元数据与意图规则 DTO 设计（梳理 `PurposeTagId`、`PurposeTagIntentRule` 的
  stage/orderWeight/replan/priority 字段）
- [ ] **T-212**: `purpose_tag` MCP 工具实现（支持 `action=LIST` 列出全部标签与 `action=GET` 读取特定标签的意图推导规则）
- [ ] **T-213**: `save_purpose_tag_rule` MCP 工具实现（支持 AI 动态配置或修正用途标签到打分意图的规则映射）
- [ ] **T-214**: 评估树草稿与落库全流程补齐 `bindingType=PURPOSE_TAG` 支持（消除 `CreateDraftRequest` 中的“暂无工具支持”限制）

### 阶段三：配置生命周期管理与高级操作 (Lifecycle & Utility Operations)

- [ ] **T-221**: `toggle_evaluator_tree_status` MCP 工具实现（支持快速启用/禁用评估树配置，无需物理删除）
- [ ] **T-222**: `clone_evaluator_tree` 与 `clone_card_group` MCP 工具实现（支持基于现有配置一键克隆副本，方便渐进式微调）
- [ ] **T-223**: 评估树与分组配置导入导出 MCP 工具实现（支持配置 JSON Bundle 的备份与恢复）

### 阶段四：工具收敛合并与 SOP 指南同步

- [ ] **T-231**: MCP 工具集中合并与瘦身审查（遵循 §4 合并原则，控制工具数量，防止 AI 上下文过载）
- [ ] **T-232**: 同步更新 `use-ai-config-generator` Skill 指引与 MCP 工具调用 SOP 规范
- [ ] **T-233**: 编写 `lin.mcp` 模块的端到端自动化单元测试（覆盖新增 MCP 工具的调用与异常边界）

---

## 挂起暂不处理任务

| 编号  | 内容                                               | 触发条件                             |
|-------|----------------------------------------------------|--------------------------------------|
| Q-201 | 是否需要暴露底层 SQLite 任意 SQL 查询 MCP 工具     | 人类确认场景需要高度自由度调试时评估 |
| Q-202 | 是否暴露实时对局推演沙盒 (Game Simulation Sandbox) | 端到端评估树评估效率瓶颈突显时启动   |
| Q-203 | `ComboPlanDefinition` 多重职责拆分评估（既负责回合内出牌打分与顺序编排，又兼顾起手换牌留牌组合加权） | 进行出牌/换牌领域模型解耦重构时评估 |
