package lin.rule.build

/**
 * 能力约束特征（Traits Bounds）
 *
 * 用于给动态配置参数 T 添加特征标签，一旦配置类实现了这些接口，
 * 在编写规则逻辑时，即可享受特定的“无参数短方法”调用（扩展作用域约束）。
 */

/**
 * 标记该规则的参数内，必定包含一组用于目标映射的基础 ID 列表。
 */
interface HasTargetIds {
    val targetIds: List<Double>
}

/**
 * (未来可扩充)
 * interface HasGroupId { val groupId: Int }
 */
