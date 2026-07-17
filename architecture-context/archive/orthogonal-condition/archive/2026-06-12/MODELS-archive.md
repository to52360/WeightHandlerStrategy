# 模型归档 - orthogonal-condition - 2026-06-12

## 已收敛与解决的骨架文件标记

### 1. [ConditionRegistry.kt](file:///g:/liw_work/jiaoBen/Deck-Plugin-Market/WeightHanderStrategy/src/main/kotlin/lin/rule/condition/ConditionRegistry.kt)

- **已解决标记**:
    - `P-004` (已解决): 拦截 `dynamic_` 前缀的动态条件编译。此项已被重构方案推翻。我们在 D-007 中升级为特征路由，并在 D-009
      中最终演进为强类型 `OrthogonalRef` 显式路由，彻底废除了魔术前缀过滤。
    - `U-004` (已解决): 特征自适应路由下可能存在的参数键命名冲突边界风险。此项已随 D-009 中推翻自适应特征路由、采用多态多维
      `ConditionPayload.OrthogonalRef` 的形式被彻底解决，参数 Map 中不再需要混入魔法控制键。
- **收敛状态**: ✅ 已收敛 (条件注册路由部分已稳定)。
