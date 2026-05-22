package lin.rule.condition

import lin.rule.tree.LogicNode

fun ConditionNode.compile(
    leafBuilder: (ConditionPayload.ConditionRef) -> ConditionLogic
): ConditionLogic {
    return when (this) {
        is LogicNode.Leaf -> {
            val payload = payload as? ConditionPayload.ConditionRef
                ?: error("Condition leaf payload must be ConditionRef")
            leafBuilder(payload)
        }

        is LogicNode.And -> {
            val compiledChildren = children.map { it.compile(leafBuilder) }
            val logic: ConditionLogic = {
                compiledChildren.all { logic -> logic(this) }
            }
            logic
        }

        is LogicNode.Or -> {
            val compiledChildren = children.map { it.compile(leafBuilder) }
            val logic: ConditionLogic = {
                compiledChildren.any { logic -> logic(this) }
            }
            logic
        }

        is LogicNode.Not -> {
            val compiledChild = child.compile(leafBuilder)
            val logic: ConditionLogic = {
                !compiledChild(this)
            }
            logic
        }

        is LogicNode.Branch -> {
            val payload = payload as? ConditionPayload.ConditionRef
                ?: error("Condition branch payload must be ConditionRef")
            val conditionLogic = leafBuilder(payload)
            val trueLogic = onTrue.compile(leafBuilder)
            val falseLogic = onFalse.compile(leafBuilder)
            val logic: ConditionLogic = {
                if (conditionLogic(this)) trueLogic(this) else falseLogic(this)
            }
            logic
        }
    }
}

fun ConditionNode.compile(
    registry: ConditionRegistry
): ConditionLogic {
    return compile { conditionRef -> registry.build(conditionRef) }
}
