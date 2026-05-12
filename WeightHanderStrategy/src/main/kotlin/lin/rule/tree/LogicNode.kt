package lin.rule.tree

/**
 * 通用的逻辑流骨架（泛型代数数据类型）
 * @param L 具体的叶子节点负载数据（业务逻辑载体）
 */
sealed interface LogicNode<out L> {
    data class And<L>(val children: List<LogicNode<L>>) : LogicNode<L>
    data class Or<L>(val children: List<LogicNode<L>>) : LogicNode<L>
    data class Not<L>(val child: LogicNode<L>) : LogicNode<L>

    /**
     * 控制流：分支（If-Then-Else）
     * 判定条件由具体的 payload 决定，如果成立走 onTrue，不成立走 onFalse
     */
    data class Branch<L>(
        val payload: L,
        val onTrue: LogicNode<L>,
        val onFalse: LogicNode<L>
    ) : LogicNode<L>

    /**
     * 叶子节点，承载具体的业务逻辑判断或规则
     */
    data class Leaf<L>(val payload: L) : LogicNode<L>
}
