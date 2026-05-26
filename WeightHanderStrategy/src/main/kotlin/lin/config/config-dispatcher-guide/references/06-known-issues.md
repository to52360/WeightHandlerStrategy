# §6 已知问题与演进方向

| 标记               | 说明                                                                                                                 |
|------------------|--------------------------------------------------------------------------------------------------------------------|
| `todo-future`    | 分管配置过渡版，存放位置待迁移                                                                                                    |
| `todo-future`    | `dispatch` 中 `cardWeightInfos` 强耦合，待新需求一起改                                                                         |
| `todo-future`    | 配置暂时放在一起，分组方案待定                                                                                                    |
| `RuleMap` 弃用     | `RuleMap(Map<RuleLevel, List<IntentRule>>)` 是旧意图规则绑定方式，已替换为 `EvaluatorTreeRoot`（AST 树结构）。`IntentRuleHandler` 已完成迁移 |
| 去重               | 多个 BindingGroupId 可能映射到相同 cardId，已在 dispatcher 层通过 `distinctBy { it.cardId }` 统一去重                                 |
| processByType 优化 | 新增 `processByType<T>` 方法，统一类型场景跳过 groupBy 和 dispatch 分桶，`processMoreConfig` 已改为委托此方法                               |

---
