# 物理基座与拓扑地图 - mcp-feature-exposure

> **定位**：记录 `lin.mcp` 模块的物理代码分层、暴露架构以及与底层 Service/DB 的映射关系。

---

## 1. 物理目录与 Provider 映射结构

`configUi` 模块下的 MCP 工具统一放置在 `configUi/src/main/kotlin/lin/mcp/` 路径下：

| MCP Tool Provider                          | 对应文件 / 类                    | 涵盖的 MCP 工具                                                                | 依赖底层 Service / DAO                                          |
|--------------------------------------------|----------------------------------|--------------------------------------------------------------------------------|-----------------------------------------------------------------|
| **CardGroupToolProvider**                  | `CardGroupToolProvider.kt`       | `card_pool`, `card_group`, `save_card_group`, `delete_card_group`              | `CardGroupQueryService`, `CardGroupService`, `HsCardRepository` |
| **AiOrthogonalToolProvider**               | `AiOrthogonalToolProvider.kt`    | `list_capability_background`, `list_orthogonal_components`                     | `OrthogonalComponentRegistry`, `StrategyCapabilityInspector`    |
| **AiTreeConfigToolProvider**               | `AiTreeConfigToolProvider.kt`    | `evaluator_tree`, `delete_evaluator_tree`                                      | `TreeConfigService`                                             |
| **AiTreeTemplateToolProvider**             | `AiTreeTemplateToolProvider.kt`  | `tree_template`, `save_evaluator_tree_template`                                | `TreeTemplateService`                                           |
| **TemplateToolProvider**                   | `TemplateToolProvider.kt`        | `template_browse`, `save_template`                                             | `LeafTemplateService`                                           |
| **AiDraftTreeToolProvider**                | `AiDraftTreeToolProvider.kt`     | `create_draft_tree`, `put_draft_leaf`, `commit_draft_tree`, `get_draft_status` | `DraftTreeService`                                              |
| *(待新增)* **ComboPlanToolProvider**       | `ComboPlanToolProvider.kt`       | `combo_plan`, `save_combo_plan`, `delete_combo_plan`                           | `ComboPlanDefinitionRepository`, `ComboPlanStore`               |
| *(待新增)* **PurposeTagToolProvider**      | `PurposeTagToolProvider.kt`      | `purpose_tag`, `save_purpose_tag_rule`                                         | `PurposeTagFinder`, `PurposeTagIntentRuleRepository`            |
| *(待新增)* **ConfigLifecycleToolProvider** | `ConfigLifecycleToolProvider.kt` | `toggle_evaluator_tree_status`, `clone_evaluator_tree`, `deck_code_import`     | `TreeConfigService`, `HearthstoneDeckCodeParser`                |

---

## 2. 交互与数据流拓扑

```mermaid
graph TD
    Client[AI LLM / Client] -->|Stdio Server Transport| Server[MyMcpServer (McpServerMain.kt)]
    Server -->|Koin Provider 汇聚| ProviderList[List<McpToolProvider>]
    
    subgraph Current Providers
        ProviderList --> CardGroupToolProvider
        ProviderList --> AiOrthogonalToolProvider
        ProviderList --> AiTreeConfigToolProvider
        ProviderList --> AiDraftTreeToolProvider
    end

    subgraph Planned Providers (V2 Exposure)
        ProviderList --> ComboPlanToolProvider
        ProviderList --> PurposeTagToolProvider
        ProviderList --> ConfigLifecycleToolProvider
    end

    ComboPlanToolProvider --> ComboPlanStore[(weightHandlerStrategy.db: combo_plan_definition)]
    PurposeTagToolProvider --> PurposeTagRule[(weightHandlerStrategy.db: purpose_tag_intent_rule)]
    ConfigLifecycleToolProvider --> DeckCodeParser[HearthstoneDeckCodeParser]
```

---

## 3. 核心接口与入口

- **MCP
  主入口**: [McpServerMain.kt](file:///g:/liw_work/jiaoBen/Deck-Plugin-Market/configUi/src/main/kotlin/lin/mcp/McpServerMain.kt)
-
**工具标准契约模型**: [McpModels.kt](file:///g:/liw_work/jiaoBen/Deck-Plugin-Market/configUi/src/main/kotlin/lin/mcp/McpModels.kt)
- **Koin DI 模块注册**: `configUi/src/main/kotlin/lin/moduls/McpModule.kt`
