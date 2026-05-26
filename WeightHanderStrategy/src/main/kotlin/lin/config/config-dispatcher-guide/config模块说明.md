---
name: config-dispatcher-guide
description: ConfigDispatcher 系统的使用与扩展说明书。当用户询问 ConfigDispatcher、CardConfig、ConfigHandler、WeightInfoFinder、BindInfoProvider 等组件，或需要在 Deck-Plugin-Market 项目中添加配置类型、注册绑定、理解数据流时使用此 Skill。
---

# ConfigDispatcher 使用与扩展指南

## 概述

`ConfigDispatcher` 是配置分发中枢，负责将**卡牌查询键（findKey）**映射到**卡牌权重信息（CardWeightInfo）**，再将**
卡牌配置（CardConfig）**按类型分派给对应的 `ConfigHandler` 处理。

核心流程：

```
BindInfoProvider (SPI)
  └─> BindInfo(findKey, cardConfigs)
        └─> ConfigDispatcher.processUniformList()
              ├─ 1. findKey → WeightInfoFinder → List<CardWeightInfo>  (查绑定)
              └─ 2. cardConfigs → ConfigHandler.processConfig()        (派配置)
```

---

## 章节索引

| 章节          | 内容                                                                                         | 参考文件                                                                     |
|-------------|--------------------------------------------------------------------------------------------|--------------------------------------------------------------------------|
| 1 定位        | 系统定位与核心流程概览                                                                                | 本文档上方概述                                                                  |
| 2 核心组件      | ConfigDispatcher、CardConfig、ConfigHandler、WeightInfoFinder、BindingGroupId、BindInfoProvider | [references/02-core-components.md](references/02-core-components.md)     |
| 3 数据流详解     | 初始化阶段、processUniformList 流程、processByType 优化路径、去重机制                                        | [references/03-data-flow.md](references/03-data-flow.md)                 |
| 4 Koin 注册方式 | ModulesLoad.configHandler 模块中的 Koin 注册代码                                                   | [references/04-koin-registration.md](references/04-koin-registration.md) |
| 5 使用场景示例    | 添加新 CardConfig 类型、SPI 注册绑定、直接调用、添加查询键类型、BindingGroupId 绑定意图规则                              | [references/05-usage-examples.md](references/05-usage-examples.md)       |
| 6 演进方向      | todo-future 标记项、RuleMap 弃用说明、去重、processByType 优化                                           | [references/06-known-issues.md](references/06-known-issues.md)           |
| 7 相关文件索引    | 所有相关源文件的路径索引                                                                               | [references/07-file-index.md](references/07-file-index.md)               |

---

## 快速参考

### 新增 CardConfig 类型（最简路径）

1. 先判断配置边界：属性/上下文类配置实现 `CardAttributeConfig`；规则类配置实现 `Rule`
2. 在 `UseConfigHandler` 或 `RuleConfigHandler` 的对应 `when` 分支中处理
3. 通过 `BindInfoProvider` 或直接调用 `processMoreConfig` 绑定

### 新增查询键类型

1. 实现 `WeightInfoFinder<YourKey>`（或使用 value class 包装，如 `BindingGroupId`）
2. 在 Koin 模块中注册
3. `ConfigDispatcher(getAll(), getAll())` 会自动收集

### 性能优化提示

当 `ids` 和 `cardConfigs` 均为统一类型时，使用 `processByType<T>()` 替代 `processUniformList()`，可跳过 `groupBy` 和
`dispatch` 分桶。

---

## 使用说明

当用户提出以下类型的问题时，加载对应参考文件：

- **"ConfigDispatcher 是什么 / 怎么工作的"** →
  本文档概述 + [references/02-core-components.md](references/02-core-components.md)
- **"数据流是怎样的 / processUniformList 流程"** → [references/03-data-flow.md](references/03-data-flow.md)
- **"怎么注册 / Koin 怎么配置"** → [references/04-koin-registration.md](references/04-koin-registration.md)
- **"怎么新增配置类型 / 怎么绑定"** → [references/05-usage-examples.md](references/05-usage-examples.md)
- **"有哪些已知问题 "** → [references/06-known-issues.md](references/06-known-issues.md)
- **"某个类在哪个文件"** → [references/07-file-index.md](references/07-file-index.md)
