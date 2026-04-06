# lin.rule 架构与 UI 说明（Skill 版）

> 范围限制：本文仅描述 `WeightHanderStrategy/src/main/kotlin/lin/rule` 目录。

## 1. 目录分层

`lin.rule` 当前可以按 4 层理解：

1. `tree`：条件树结构与树实例化
2. `build`：规则注册模型、构建上下文、字段声明 DSL
3. `registry`：规则仓库、参数校验、参数解析器
4. `handler`：旧意图执行链（兼容/历史链路）

### 1.1 tree 层（结构与实例）

- `tree/ConditionNode.kt`
    - 纯结构树：`RuleNode(nodeId)` / `AndNode` / `OrNode` / `NotNode`
- `tree/ConditionTreeConfig.kt`
    - 配置输入：`bindIds + root + ruleConfigs`
    - `RuleConfig` 保存叶子配置：`nodeId + ruleId + depByWeightIds + args`
- `tree/ConditionTreeInstance.kt`
    - `ConditionTreeInstantiator` 将配置树实例化为可绑定对象
    - 叶子节点实例包含 `RuleLogic`

### 1.2 build 层（规则定义）

- `build/RuleBean.kt`
    - `RuleRegistration<T>`：规则注册单元
    - `RuleSpec<T>`：`argsParser + ruleFactory`
    - `RuleMetadata`：UI 元数据 + 动态字段声明
    - `DynamicField` 支持：
        - `type`（`INT/BOOLEAN/STRING`）
        - `required`
        - `regex`
        - `options`（下拉）
- `build/RuleBuilder.kt`
    - 泛型 `RuleBuilder<T>`
    - `ruleBuilder(argsParser)` 作为入口
    - 动态字段 DSL（含类型快捷与下拉快捷）
- `build/RuleBuildContext.kt`
    - `RuleBuildContext<T>`，核心点：
        - `params: T`（强类型参数）
        - `ruleConfig`（原始配置）
        - 领域工具（`cardWeights/races/isRace/isGroup` 等）
        - `parseArgs(...)`（可选二次解析）

### 1.3 registry 层（装配与校验）

- `registry/RuleRegistrationProvider.kt`
    - SPI 提供规则注册：`Collection<RuleRegistration<*>>`
- `registry/RuleConfigValidator.kt`
    - 参数校验逻辑（从 `RuleRegistry` 拆出）
    - JSON Schema 输出能力
- `registry/RuleArgsParser.kt`
    - `RuleArgsParser<T>` 强类型参数解析
    - `ruleArgsParser { ... }` 快捷构造器
    - `RuleArgsReader` 提供 `int/bool/string` 读取
- `registry/RuleRegistry.kt`
    - 规则索引、UI 元数据查询、构建 `RuleLogic`
    - 构建流程：
        1. 按 `ruleId` 找注册项
        2. 走 validator 校验 `args`
        3. 用 `argsParser` 解析出 `T`
        4. 调用 `ruleFactory(ruleConfig, params)`

### 1.4 handler 层（旧链路）

- `handler/*` 与 `RuleInfoRegister.kt` 仍是历史逻辑。
- 新树模型与新注册模型优先走 `tree + build + registry`。

## 2. 核心数据流（新链路）

1. UI/配置端产出 `ConditionTreeConfig`
2. `ConditionTreeInstantiator` 遍历 `ConditionNode.RuleNode(nodeId)`
3. 从 `ruleConfigs[nodeId]` 取 `RuleConfig`
4. `RuleRegistry.build(ruleConfig)` 构建 `RuleLogic`
5. 形成 `ConditionTreeInstance`，绑定到 card 使用

关键约束：

- 结构引用用 `nodeId`
- 规则实现引用用 `ruleId`
- `bindIds` 属于树级别，不属于单个 `RuleConfig`

## 3. 规则扩展模板

## 3.1 定义参数对象

```kotlin
data class HandRuleParams(
    val min: Int,
    val includeCoin: Boolean
)
```

## 3.2 定义解析器

```kotlin
import lin.rule.registry.ruleArgsParser

val handRuleParser = ruleArgsParser {
    HandRuleParams(
        min = int("min"),
        includeCoin = bool("includeCoin")
    )
}
```

## 3.3 注册规则

```kotlin
import lin.rule.build.ruleBuilder

val registration = ruleBuilder(handRuleParser)
    .id("HandCountRule")
    .metadata(name = "手牌数量规则", desc = "按参数判断")
    .requireIntField("min")
    .requireBooleanField("includeCoin")
    .factory { ruleConfig, params ->
        // params 是 HandRuleParams
        RuleLogic { callCard, warInfo ->
            // TODO 规则逻辑
            lin.rule.handler.SkipResult
        }
    }
    .build()
```

## 4. UI 对接说明

## 4.1 UI 获取规则列表

调用：

- `RuleRegistry.uiItems()`

返回项 `RuleUiItem` 含：

- `ruleId`
- `name`
- `desc`
- `dynamicFields`

用途：

- 渲染规则选择器
- 渲染参数表单（根据 `dynamicFields`）

## 4.2 UI 获取单规则 JSON Schema

调用：

- `RuleRegistry.jsonSchema(ruleId)`

用途：

- 前端按 schema 自动生成/校验表单
- 或后端统一复用 schema

说明：

- schema 基于 `DynamicField` 生成
- `options` 会映射为 `enum`
- `required=true` 会进入 `required` 列表

## 4.3 字段渲染规范

`DynamicField.type` 到控件：

- `INT` -> 数字输入
- `BOOLEAN` -> 开关/单选
- `STRING` -> 文本输入

`DynamicField.options` 非空时：

- 统一渲染为下拉
- 展示 `label`，提交 `value`
- 提交值写入 `RuleConfig.args[propertyName]`

`DynamicField.regex` 非空时：

- 前端可即时校验
- 后端 `RuleConfigValidator` 会再次校验（不可省略）

## 4.4 条件树编辑规范

前端树节点保存：

- 结构树：`ConditionNode`（叶子只放 `nodeId`）
- 配置映射：`ruleConfigs[nodeId] = RuleConfig`

禁止做法：

- 在 `ConditionNode.RuleNode` 内直接塞 `ruleId/args`

推荐做法：

- 结构和配置分离，便于复用与增量编辑

## 5. Skill 使用建议

如果把这套能力做成技能，建议技能步骤固定为：

1. 读取 `tree/ConditionTreeConfig` 与 `ConditionNode`
2. 校验每个 `nodeId` 是否有 `RuleConfig`
3. 校验每个 `RuleConfig.ruleId` 是否在 `RuleRegistry` 中存在
4. 用 `RuleRegistry.jsonSchema(ruleId)` 校验 `args`
5. 输出可实例化的 `ConditionTreeConfig`

## 6. 已知边界

- `handler` 与 `RuleInfoRegister` 属于旧链路，尚未完全并入新链路。
- `RuleRegistry` 内部仍需一次泛型擦除转换（`RuleRegistration<*>` -> `RuleRegistration<Any>`），这是 Kotlin 运行时泛型限制导致的实现细节。
