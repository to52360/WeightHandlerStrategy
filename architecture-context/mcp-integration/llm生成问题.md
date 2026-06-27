**可以直接落地的设计方案**，目标是：

> ✔ 保持你现在 DSL / Transform / Rule 结构
> ✔ 解决“组合自由导致不可控”的问题


---

# 1. 总体设计（核心升级点）

你现在缺的是一层：

> ## ⭐ Transform Graph Constraint + Rule Composition Layer（TGCC）

整体结构变成：

```text id="arch1"
CardDeck
   ↓
Feature Extractor
   ↓
Group Planner（LLM，可选）
   ↓
Graph Composer（规则 + 约束核心）
   ↓
Rule Binding Engine
   ↓
DSL Validator
   ↓
Executor
```

---

# 2. 核心设计一：Transform Graph Model（关键）

把 Transform 从“函数”升级成“图节点约束对象”。

---

## ① Transform节点定义（增强版）

你已经有：

```kotlin id="t1"
Transform<In, Out>
```

再加三个关键约束：

```kotlin id="t2"
val category: TransformCategory
val tags: Set<String>
val constraints: TransformConstraints
```

---

## ② TransformCategory（核心分类）

强制分层：

```text id="cat1"
SOURCE       → 数据源
FILTER       → 过滤
MAP          → 转换
AGGREGATE    → 聚合
FEATURE      → 特征计算
DECISION     → 比较 / 判定
```

👉 关键点：

> ❗ LLM 只能在 category 内组合，不能跨结构乱连

---

## ③ Graph连接规则（最重要）

定义一个“合法连接矩阵”：

```text id="matrix1"
SOURCE → FILTER / MAP
FILTER → FILTER / MAP / AGGREGATE
MAP → FILTER / AGGREGATE / FEATURE
AGGREGATE → FEATURE / DECISION
FEATURE → DECISION
DECISION → SCORE
```

👉 这一步直接解决：

* pipeline乱序
* 类型漂移
* 语义错配

---

# 3. 核心设计二：Rule Composition Model

你现在：

```kotlin id="rule1"
EvaluatorLeafConfig
```

建议补一个：

> ## RuleGroup（策略单元）

---

## RuleGroup结构

```text id="rg1"
RuleGroup
  - featurePipeline
  - rules[]
  - scoreStrategy
```

---

## Rule绑定规则（关键）

```text id="bind1"
FeaturePipeline → 必须输出 FEATURE
Rule → 必须 consume FEATURE
GuardCondition → 必须 consume FEATURE or DECISION
```

---

# 4. 核心设计三：Graph Composer（核心引擎）

这一层不是 LLM，是确定性系统。

---

## 输入：

```text id="gc1"
Card features + available transforms + group plan
```

---

## 输出：

```text id="gc2"
Validated Transform Graph + Rule binding
```

---

## 作用：

### ① 过滤非法组合

* type mismatch
* category mismatch
* cycle detection

---

### ② 自动修正路径

例如：

```text id="fix1"
FILTER → DECISION ❌
```

自动插入：

```text id="fix2"
FILTER → AGGREGATE → DECISION ✔
```

---

### ③ 强制结构收敛

避免 LLM 自由拼 graph

---

# 5. 核心设计四：LLM 使用方式（非常关键）

LLM 在这里只做三件事：

---

## ✔ ① Group Planner

```text id="llm1"
CardDeck → Aggro / Control / Combo groups
```

---

## ✔ ② Rule Suggestion（不是生成）

```text id="llm2"
建议用哪些已有 Rule + 参数
```

---

## ✔ ③ Repair Suggestion（diff）

```text id="llm3"
invalid DSL → patch
```

---

## ❌ 绝对禁止：

* 生成 Transform graph
* 自由拼 pipeline
* 决定 category flow

---

# 6. Validator（升级版，不只是校验）

你现在的 validator 要升级为：

## ⭐ 3层验证

---

### ① Schema Validation（你已有）

* 类型
* 字段
* JSON结构

---

### ② Type Flow Validation（增强）

```text id="v1"
InType → OutType 是否匹配
```

---

### ③ Semantic Constraint Validation（关键）

```text id="v2"
FILTER → DECISION 是否缺 AGGREGATE
Rule是否缺 FEATURE
GuardCondition是否跨层
```

---

# 7. Repair System（你现在最缺的）

不是 MCP，是：

> ## DSL Patch Engine

---

## 输入：

```text id="r1"
invalid DSL + validation report
```

---

## 输出：

```text id="r2"
minimal patch diff
```

---

## Patch例子：

```text id="p1"
- remove transform X
+ insert aggregate Y
- threshold: 123
+ threshold: 8
```

---


---

# 9. 最终架构（收敛版）

```text id="final"
CardDeck
   ↓
Feature Extractor
   ↓
LLM Group Planner
   ↓
Graph Composer (STRICT)
   ↓
Rule Binding Engine
   ↓
Validator (3-layer)
   ↓
Repair Engine (LLM patch)
   ↓
DSL Store
   ↓
Executor
```



