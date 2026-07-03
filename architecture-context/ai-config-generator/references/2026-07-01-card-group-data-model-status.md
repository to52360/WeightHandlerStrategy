# 现状汇总 — 卡牌分组与用途数据模型

> 日期: 2026-07-01
> 目的: 梳理现有卡牌分组(card_group)、用途(card_purpose)、卡牌目录(card_catalog)的数据模型与依赖关系，不做方案建议。

---

## 1. 数据来源总览

### 1.1 .cardgroup 文件（主程序提供）

- 物理位置：主程序 `data/cardgroup/` 目录，`.cardgroup` 扩展名
- 内容结构（`CardGroupConfig`）：
  ```json
  {
    "enabled": true,
    "cards": [
      { "cardId": "EX1_001", "name": "Lightwarden" },
      ...
    ]
  }
  ```
- **主程序负责生成和维护**，本项目只读取，不能随意增删卡
- 每个 `.cardgroup` 文件代表一个卡组（通常是标准套牌）
- 每张卡只有 `cardId + name`，没有费用/类型/描述/权重

### 1.2 hs_cards.db（游戏卡牌参考库）

- 主程序附带的 SQLite 数据库，包含所有标准扩展包的卡牌完整定义
- 字段包含：cardId、name、cost、type、cardClass、text、flavorText 等
- 本项目当前**未直接查询此库**，仅作为主程序的参考源

### 1.3 weightHandlerStrategy.db（项目主库）

- Spring JDBC 连接的 SQLite，本项目所有持久化数据的位置

---

## 2. DB 表结构

### 2.1 card_group_manager

| 字段         | 类型      | 说明                |
|------------|---------|-------------------|
| id         | TEXT PK | UUID              |
| name       | TEXT    | 方案名称              |
| sourceFile | TEXT    | 来源 .cardgroup 文件名 |
| enabled    | INTEGER | 是否启用              |

### 2.2 card_group_binding

| 字段        | 类型                           | 说明                    |
|-----------|------------------------------|-----------------------|
| id        | TEXT PK                      | UUID                  |
| managerId | TEXT FK → card_group_manager | 所属 Manager            |
| name      | TEXT                         | 分组名称（如"AOE""单体伤害"）    |
| cardIds   | TEXT                         | JSON 数组，引用卡牌          |
| overrides | TEXT                         | GroupUseOverride JSON |

### 2.3 card_catalog

| 字段           | 类型      | 说明   |
|--------------|---------|------|
| card_id      | TEXT PK | 卡牌ID |
| name         | TEXT    | 卡牌名称 |
| created_date | TEXT    | 导入日期 |

### 2.4 card_purpose

| 字段               | 类型      | 说明        |
|------------------|---------|-----------|
| card_id          | TEXT PK | 卡牌ID      |
| purpose_tags     | TEXT    | 逗号分隔的用途标签 |
| replan_after_use | INTEGER | 出牌后是否重规划  |

---

## 3. 运行时领域模型

### 3.1 WeightHanderStrategy 引擎侧

```
CardGroupManagerConfig
├── cardGroupManagerId: String
├── name: String
├── enabled: Boolean
└── bindings: List<CardGroupBinding>
    ├── id: String
    ├── managerId: String
    ├── name: String
    ├── cardIds: List<String>        ← 有哪些卡
    └── overrides: GroupUseOverride?  ← 这张卡在这个分组里的行为覆盖
        ├── stageOverride: UseStage?
        ├── replanAfterUse: Boolean?
        └── orderWeight: Double?     ← 排序权重（跟随分组）
```

### 3.2 configUi 配置侧

```
CardManagerEntity      ← DB 映射
CardBindingEntity      ← DB 映射
CardWeightConfig       ← .cardgroup 文件映射 (cardId + name)
CardPurposeEntity      ← card_catalog JOIN card_purpose 的展示模型
CardPurpose            ← 领域层用途 (purposeTags + replanAfterUse)
GroupUseOverride       ← 分组级行为覆盖 (stageOverride + replanAfterUse + orderWeight)
```

---

## 4. 数据流转关系

### 4.1 导入链路（当前）

```
┌─────────────────────────┐
│  主程序生成 .cardgroup   │
│  (cardId + name 列表)    │
└────────────┬────────────┘
             │ CardGroupJsonParser.loadAllCardGroups()
             ▼
┌─────────────────────────┐
│  CardPurposeStore       │
│  .importCardGroup()     │
│  ↓                      │
│  syncCards() ──────────→│──── card_catalog (INSERT/UPDATE cardId + name)
│  逐卡写入               │
└─────────────────────────┘
```

`importCardGroup()` 是"导入卡组卡牌到用途系统"的唯一入口。没有这个步骤，card_catalog 没有数据，card_purpose 无法创建。

### 4.2 配置侧读取（UI 工作台）

```
CardPurposeStore.loadInitialData()
├── CardGroupJsonParser.listAvailableFiles()  ← 读取 .cardgroup 文件名列表
└── CardPurposeRepository.getAvailableDates() ← 查 card_catalog 已有日期

CardPurposeStore.loadPage(filter)
├── 根据 groupName 调 CardGroupJsonParser.loadByFileName()  ← 过滤到具体卡的 cardIds
└── CardPurposeRepository.findPaginated(cardIds, search, ...)  ← 分页查用途配置
```

**⚠ 关键依赖：card_purpose 的 UI 过滤依赖 .cardgroup 文件内容来确定"这个分组有哪些卡"**

### 4.3 分组工作台（card_group/ui/）

```
CardGroupWorkbench → CardGroupService → CardGroupRepository
├── loadAll()  → 从 DB 读取 manager + binding（已持久化的分组方案）
├── saveManager() → 写入 DB
└── 编辑 binding 时选卡：
    └── cardPool 来自 WorkbenchState.currentCardPool
        └── 数据源：.cardgroup 文件中的 cards 列表
            （通过 CardGroupJsonParser.loadByFileName() 获取）
```

**⚠ 当前新建 Binding 时选卡，只能从 .cardgroup 文件的 cards 池中选择**

### 4.4 引擎运行时（WeightHandlerStrategy）

```
启动时:
CardGroupIndexProvider (SPI)
├── 从 DB 读取 enabled=true 的 Manager
└── 加载所有 Binding
    └── 引擎内部用 cardIds + overrides 做决策

引擎不读 .cardgroup 文件，只读 DB
```

---

## 5. 现有关系图

```
.cardgroup (主程序)
    │
    ├─→ card_catalog          [通过 importCardGroup() 同步]
    │       │
    │       └─→ card_purpose  [通过 cardId 人工/AI 配置]
    │
    └─→ card_group_manager    [通过 UI 工作台手动创建]
            │
            └─→ card_group_binding  [通过 UI 工作台配置]
                    │
                    ├─ cardIds: 引用的卡必须在 .cardgroup 池中
                    └─ overrides: 这个分组内的行为覆盖（跟随分组）
```

---

## 6. 关键约束

1. **卡牌元数据只能从主程序获取** — hs_cards.db 主程序独占，本项目不能随意增减卡
2. **卡组池来自 .cardgroup 文件** — 主程序(我现在开发是插件)通过 UI 生成，本项目只读
3. **card_catalog 当前是从 .cardgroup 导入的冗余** — 仅多了 created_date 字段 (批注:在考虑要不要删除)
5. **card_purpose 是卡全局属性** — purposeTags 描述卡牌的战略用途，与分组无关
6. **AI 完全不知晓分组系统存在** — 当前 MCP 工具无法查询/创建分组

---

## 7. 当前痛点（事实描述，非方案）

| #    | 现象                                                        | 涉及的层                            |
|------|-----------------------------------------------------------|---------------------------------|
| P-01 | card_purpose 的导入依赖 importCardGroup() 主动触发    (批注:这里可能要调整) | CardPurposeStore ↔ .cardgroup   |
| P-02 | card_group 选卡时只能从 .cardgroup 文件池内选择                       | CardGroupWorkbench ↔ .cardgroup |
| P-03 | 同一张卡在不同 Manager 的 .cardgroup 中出现重复                        | 主程序生成的 .cardgroup 文件各自独立        |
| P-04 | card_catalog 只有 card_id/name，描述等元数据缺失                     | card_catalog 表结构                |
| P-05 | card_purpose 和 card_group 是两套独立 UI，配置需切换                  | 两个 Workbench                    |
| P-06 | AI MCP 暴露的 3 个工具与分组用途配置完全无交集(批注:本来不是这次任务,因为涉及然后也一起考虑)     | McpToolRouter                   |

---

## 附录：关键源文件索引

| 文件                                                                          | 职责                                             |
|-----------------------------------------------------------------------------|------------------------------------------------|
| `configUi/.../dao/CardGroupJsonParser.kt`                                   | 解析 .cardgroup JSON 文件                          |
| `configUi/.../card_group/db/CardGroupEntities.kt`                           | CardManagerEntity / CardBindingEntity          |
| `configUi/.../card_group/db/CardGroupRepository.kt`                         | card_group_manager / card_group_binding CRUD   |
| `configUi/.../card_group/db/CardGroupService.kt`                            | 分组域服务                                          |
| `configUi/.../card_group/ui/WorkbenchState.kt`                              | 分组工作台状态树                                       |
| `configUi/.../card_group/ui/CardGroupWorkbench.kt`                          | 分组工作台 UI                                       |
| `configUi/.../card_purpose/db/CardPurposeRepository.kt`                     | card_catalog / card_purpose CRUD               |
| `configUi/.../card_purpose/ui/CardPurposeStore.kt`                          | 用途工作台 Store（含 importCardGroup）                 |
| `configUi/.../card_purpose/ui/CardPurposeWorkbench.kt`                      | 用途工作台 UI                                       |
| `configUi/.../ai/config/DefaultAiConfigGenerationService.kt`                | AI 配置生成主服务                                     |
| `configUi/.../mcp/McpToolRouter.kt`                                         | MCP tool 路由                                    |
| `WeightHanderStrategy/.../rule/tree/CardGroupBinding.kt`                    | CardGroupBinding / CardGroupManagerConfig 领域模型 |
| `WeightHanderStrategy/.../bean/usePlan/UseIntent.kt`                        | GroupUseOverride / CardPurpose 领域模型            |
| `WeightHanderStrategy/.../serviceLoader/provider/CardGroupIndexProvider.kt` | 引擎运行时分组加载 SPI                                  |
