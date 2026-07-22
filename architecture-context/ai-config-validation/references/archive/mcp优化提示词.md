# MCP Tool 整理 Skill

## 目标

将面向业务 API 的大量 MCP Tool，整理为适合 LLM Agent 理解和调用的工具集合。

核心原则：

> MCP Tool 面向 Agent 意图设计，而不是面向后端 API 设计。

不要让 Agent 学习系统内部接口，而应该让 Agent 使用业务动作。

---

# 一、Tool 数量评估

## 不以数量作为唯一标准

Tool 数量不是越少越好。

判断标准：

* Tool 描述是否表达独立业务意图
* LLM 是否容易判断什么时候调用
* 参数结构是否清晰
* Tool 返回结果是否符合 Agent 下一步推理需要

经验范围：

| Tool数量 | 状态           |
| ------ | ------------ |
| 1~5    | 可能过度聚合       |
| 5~15   | 通常合理         |
| 15~30  | 需要检查粒度       |
| 30+    | 大概率 API 暴露过度 |

---

# 二、禁止 API 映射式 MCP

## 错误设计

后端：

```
create_rule()
update_rule()
delete_rule()
get_rule()
list_rule()
validate_rule()
```

直接暴露：

```
mcp_create_rule
mcp_update_rule
mcp_delete_rule
...
```

问题：

1. Agent 不理解业务流程
2. Tool 选择空间过大
3. 容易调用错误
4. Agent 需要自己组合业务流程

---

# 三、按业务能力聚合

## 推荐结构

```
MCP Layer

    |
    |
Business Tool

    |
    |
Domain Service

    |
    |
Repository / API
```

例如：

不要：

```
search_card
get_card
get_card_tag
get_card_cost
```

改为：

```
card_analysis
```

参数：

```json
{
  "operation": "ANALYZE_DECK",
  "deck": []
}
```

---

# 四、Tool 合并规则

## 应该合并

满足：

* 同一个业务领域
* 相似输入
* 相似输出
* 同一个 Agent 阶段使用

例如：

```
search_rule
get_rule_detail
explain_rule
find_rule_by_tag
```

合并：

```
rule_knowledge
```

---

## 不应该合并

如果：

* 风险不同
* 生命周期不同
* 权限不同
* 副作用不同

保持独立。

例如：

```
query_config

apply_config
```

不要合并。

因为：

查询：

```
read only
```

修改：

```
side effect
```

Agent 需要区别处理。

---

# 五、不要隐藏 Tool 能力

## 错误

```json
{
 "tool":"game_tool",
 "action":"xxx"
}
```

问题：

LLM 只看到：

```
game_tool
```

不知道：

* action有哪些
* 什么情况下使用
* 参数要求

---

## 推荐

领域 Tool：

```
rule_management
```

内部：

```json
{
 "operation":
 [
   "SEARCH",
   "EXPLAIN",
   "VALIDATE"
 ]
}
```

Tool description 明确说明：

```
用于查询和分析规则。
支持:
- 查找已有规则
- 解释规则用途
- 验证规则组合
```

---

# 六、MCP Tool 分层设计

推荐三层：

```
L1 任务工具

    strategy_generate


L2 领域工具

    card_analysis
    rule_management
    config_management


L3 系统能力

    database
    filesystem
    api
```

Agent 默认只看到：

L1 + L2

不要暴露：

L3。

---

# 七、Agent 场景设计

设计 MCP 时先问：

## 用户想完成什么？

例如：

用户：

> 根据卡组生成打法策略

不要设计：

```
create_group
bind_rule
create_condition
save_config
```

应该设计：

```
generate_strategy
```

内部流程：

```
分析卡组

↓

寻找已有规则

↓

组合策略

↓

生成配置

↓

验证

↓

返回结果
```

---

# 八、返回结果设计

MCP 主要提供基础数据和明确的执行结果，不负责替 Agent 完成策略分析。

不要返回难以理解的数据库结构。

错误：

```json
{
"id":123,
"rule_id":"xxx",
"config_json":"..."
}
```

推荐返回面向 Agent 的结构化信息：

```json
{
"rules":[
 {
   "id":"r001",
   "name":"低费爆发",
   "description":"前期提高资源利用率"
 }
],
"validation":{
 "valid":true,
 "errors":[]
}
}
```

MCP 应该提供：

* 基础事实数据
* 可用规则和配置资产
* 校验结果
* 执行结果

而不是直接返回：

```json
{
"summary":"该策略适合快攻卡组",
"reason":"前期提高资源利用率"
}
```

这类策略判断应该由 Agent 根据上下文推理完成。

MCP 负责提供信息和能力，Agent 负责理解、组合和决策。

---

# 九、MCP 重构检查流程

## Step 1

列出所有 Tool：

```
tool_name
input
output
purpose
```

---

## Step 2

标记类型：

```
查询
修改
分析
生成
执行
```

---

## Step 3

寻找重复领域：

例如：

```
card_xxx 10个

rule_xxx 8个

config_xxx 6个
```

聚合。

---

## Step 4

重新定义 Agent 能力：

例如：

从：

```
24个API Tool
```

变成：

```
card_analysis

rule_reasoning

strategy_generation

config_management

simulation
```

---

# 十、最终原则

MCP Tool 的目标：

不是：

> 让 AI 能调用所有系统能力

而是：

> 让 AI 能完成任务，并且知道下一步应该做什么。

好的 MCP：

```
少量业务能力入口

+
明确语义

+
安全边界

+
稳定返回结构
```

坏的 MCP：

```
大量 CRUD API

+
复杂参数

+
让 Agent 自己编排业务流程
```
