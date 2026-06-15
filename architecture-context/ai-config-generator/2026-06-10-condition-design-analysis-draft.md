# 评估草案：条件类正交组合与原子性设计分析

## 1. 背景与目标

### 1.1 当前痛点

现有条件系统采用“单参数原子类”设计，每个条件（如 `HandCardCountCondition`、`BattlefieldRaceExistsCondition`
）均为独立硬编码实现。随着业务维度扩展（数据源 × 判断对象 × 比较算子），条件数量呈乘积级增长，导致：

- **开发成本高**：每新增一个维度组合需新建完整条件类
- **维护困难**：相同算子逻辑（如 `>=`、`contains`）在多个条件中重复实现
- **扩展性差**：新增数据源（如“墓地”）无法自动复用已有算子

### 1.2 重构目标

- **消除乘积组合**：将条件拆解为正交组件，通过组合而非继承生成新条件
- **保持树结构稳定**：确保 `LogicNode<ConditionPayload>` 条件树架构零侵入、零修改
- **保留原子性语义**：组合后的条件对外仍表现为单一原子节点，对上层透明
- **支持元数据驱动**：通过类别标签实现前端/编辑器的智能联动与过滤

---

## 2. 核心架构设计

### 2.1 三层解耦模型

```
┌──────────────────────────────────────────────┐
│           逻辑编排层 (Logic Tree)             │
│  LogicNode<ConditionPayload> / And/Or/Not    │
│  ✅ 完全不变，仅操作 ConditionRef            │
├──────────────────────────────────────────────┤
│           条件装配层 (Condition Assembly)     │
│  ConditionRegistry + DynamicFactory          │
│  将 (Source + Operator + Args) → ConditionRef│
├──────────────────────────────────────────────┤
│           原子组件层 (Atomic Components)      │
│  DataSource / Operator / Category Metadata   │
│  正交、无状态、可独立测试                     │
└──────────────────────────────────────────────┘
```

### 2.2 与现有条件树的兼容性保证

| 现有结构                            | 重构影响    | 说明                     |
|:--------------------------------|:--------|:-----------------------|
| `LogicNode<T>`                  | ✅ 无影响   | 泛型结构不感知 Payload 内部实现   |
| `ConditionPayload.ConditionRef` | ✅ 无影响   | 仅作为引用标识，args 字段天然支持参数化 |
| `collectConditionRefs()`        | ✅ 无影响   | 按 refId 去重，与条件生成方式无关   |
| `ConditionBuilder` DSL          | ⚠️ 小幅调整 | 从注册具体条件改为注册模板+绑定辅助函数   |

**核心原则**：条件树只负责“逻辑编排”，不负责“条件生产”。底层如何组装条件，对树完全透明。

---

## 3. 原子组件规范

### 3.1 数据源提供者 (DataSource)

```kotlin
/**
 * 数据源：只负责从上下文中提取原始数据，不含任何判断逻辑
 */
interface DataSource<out T> {
    val id: String
    val categories: Set<String>       // 如 {"手牌", "数量"}
    val outputType: KClass<*>         // 用于前端类型匹配
    fun resolve(context: RuleContext): T
}

// 示例
object HandCardCountSource : DataSource<Int> {
    override val id = "hand_card_count"
    override val categories = setOf("手牌", "数量")
    override val outputType = Int::class
    override fun resolve(context: RuleContext): Int = context.handCards.size
}

object BattlefieldRacesSource : DataSource<Set<CardRace>> {
    override val id = "battlefield_races"
    override val categories = setOf("战场", "种族", "集合")
    override val outputType = Set::class
    override fun resolve(context: RuleContext): Set<CardRace> =
        context.battlefield.map { it.race }.toSet()
}
```

### 3.2 通用算子 (Operator)

```kotlin
/**
 * 算子：纯函数式比较逻辑，与业务数据完全无关
 */
interface Operator<I> {
    val id: String
    val categories: Set<String>       // 如 {"数量", "比较"}
    val inputType: KClass<*>          // 必须与 DataSource.outputType 匹配
    fun evaluate(input: I, args: Map<String, Any>): Boolean
}

// 示例
object GreaterThanOrEqualOp : Operator<Int> {
    override val id = "gte"
    override val categories = setOf("数量", "比较")
    override val inputType = Int::class
    override fun evaluate(input: Int, args: Map<String, Any>): Boolean {
        val threshold = args["threshold"] as? Int ?: 0
        return input >= threshold
    }
}

object ContainsOp : Operator<Set<*>> {
    override val id = "contains"
    override val categories = setOf("集合", "存在性")
    override val inputType = Set::class
    override fun evaluate(input: Set<*>, args: Map<String, Any>): Boolean {
        val target = args["target"] ?: return false
        return input.contains(target)
    }
}
```

### 3.3 类别元数据体系

```kotlin
/**
 * 类别注册表：用于前端/编辑器智能过滤
 */
object CategoryRegistry {
    // 数据源按类别索引
    fun sourcesByCategory(category: String): List<DataSource<*>>
    // 根据数据源输出类型，返回兼容的算子列表
    fun compatibleOperators(outputType: KClass<*>): List<Operator<*>>
    // 根据已选数据源类别，推荐相关算子类别
    fun suggestedOperatorCategories(sourceCategories: Set<String>): Set<String>
}
```

**前端联动规则**：

1. 用户选择数据源 → 获取其 `outputType` 和 `categories`
2. 系统自动过滤出 `inputType` 匹配的算子
3. 优先展示 `categories` 有交集的算子
4. 用户感知为线性选择流程，而非 M×N 矩阵

---

## 4. 条件装配层实现

### 4.1 动态条件工厂

```kotlin
/**
 * 将 (DataSource + Operator + Args) 装配为可执行条件
 * 对外产出仍然是标准的 ConditionRef，对条件树完全透明
 */
object ConditionAssembler {

    fun assemble(
        sourceId: String,
        operatorId: String,
        args: Map<String, Any>,
        refId: String = "${sourceId}_${operatorId}_${args.hashCode()}"
    ): ConditionPayload.ConditionRef {
        // 校验类型兼容性
        val source = DataSourceRegistry[sourceId]
        val operator = OperatorRegistry[operatorId]
        require(operator.inputType == source.outputType) {
            "类型不匹配: ${source.outputType} vs ${operator.inputType}"
        }
        return ConditionPayload.ConditionRef(
            conditionId = "dynamic_${sourceId}_${operatorId}",
            refId = refId,
            args = args + mapOf("_sourceId" to sourceId, "_operatorId" to operatorId)
        )
    }

    /**
     * 运行时解析：由条件执行引擎调用
     */
    fun execute(ref: ConditionPayload.ConditionRef, context: RuleContext): Boolean {
        val sourceId = ref.args["_sourceId"] as String
        val operatorId = ref.args["_operatorId"] as String
        val source = DataSourceRegistry[sourceId]
        val operator = OperatorRegistry[operatorId]
        val input = source.resolve(context)
        @Suppress("UNCHECKED_CAST")
        return (operator as Operator<Any>).evaluate(input, ref.args)
    }
}
```

### 4.2 DSL 辅助函数（保持易用性）

```kotlin
// 高层封装：让业务代码仍然简洁，隐藏组合细节
fun handCardCountAtLeast(min: Int): ConditionPayload.ConditionRef =
    ConditionAssembler.assemble("hand_card_count", "gte", mapOf("threshold" to min))

fun battlefieldContainsRace(race: CardRace): ConditionPayload.ConditionRef =
    ConditionAssembler.assemble("battlefield_races", "contains", mapOf("target" to race))

// 在条件树中使用，与之前完全一致
val rule = and(
    leaf(handCardCountAtLeast(3)),
    or(
        leaf(battlefieldContainsRace(CardRace.DRAGON)),
        not(leaf(graveyardIsEmpty()))
    )
)
```

---

## 5. 迁移策略

### 5.1 渐进式迁移路径

| 阶段      | 工作内容                                     | 风险等级           |
|:--------|:-----------------------------------------|:---------------|
| Phase 0 | 定义 DataSource / Operator 接口及类别元数据        | 🟢 零风险         |
| Phase 1 | 实现 ConditionAssembler + 动态执行引擎           | 🟢 零风险（新增代码）   |
| Phase 2 | 将高频复用的算子（>=, ==, contains）抽取为通用 Operator | 🟡 低风险         |
| Phase 3 | 将现有硬编码条件逐个替换为 (Source+Operator) 组合       | 🟡 低风险(没有正式使用) |
| Phase 4 | 接入前端/编辑器类别联动                             | 🟢 零风险         |

## 6. 收益评估

| 指标      | 重构前               | 重构后                      |
|:--------|:------------------|:-------------------------|
| 新增数据源成本 | N 个新条件类（N=已有算子数）  | 1 个 DataSource 实现        |
| 新增算子成本  | M 个新条件类（M=已有数据源数） | 1 个 Operator 实现          |
| 条件总数    | M × N × K         | M + N + K                |
| 条件树改动   | —                 | **零改动**                  |
| 前端配置体验  | 平铺枚举或手动筛选         | 类别联动、智能过滤                |
| 单元测试粒度  | 按完整条件测试           | 按 Source / Operator 独立测试 |

---

## 7. 注意事项

1. **类型安全**：`DataSource.outputType` 与 `Operator.inputType` 的匹配校验必须在装配时完成，避免运行时 ClassCastException
2. **refId 稳定性**：动态生成的 `refId` 必须确定性（相同输入产生相同 refId），否则 `collectConditionRefs()` 去重和序列化/反序列化会出错
3. **性能**：动态装配引入一层间接寻址，对于高频执行的条件可考虑缓存已解析的执行器实例
4. **调试友好**：建议在 `ConditionRef.args` 中保留人类可读的描述字段，便于日志追踪和错误排查

## 核心未决问题与下一步研究点

1. **引擎是否需要支持动态权重计算**：以数量计算权重（如每张野兽权重 +10）属于定量计算，只能放在评估树的叶子（`Rule`
   ）中动态返回分值，而不能依靠返回 Boolean 的 `Condition`。需要理清 Rule 层的正交化设计。
2. **UI 编辑器的渲染能力**：如果走向多参数或复合对象，`configUi` 能否优雅地动态生成对应的表单。
3. **AI 生成效能**：单参数原子条件树组合 VS 多参数扁平配置，哪种对于大模型（MCP 辅助生成）的准确度更高、幻觉更少。
