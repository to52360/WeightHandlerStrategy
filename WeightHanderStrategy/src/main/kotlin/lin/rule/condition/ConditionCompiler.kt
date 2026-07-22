package lin.rule.condition

import lin.rule.tree.LogicNode

fun ConditionNode.compile(
    leafBuilder: (ConditionPayload) -> ConditionLogic
): ConditionLogic {
    return when (this) {
        is LogicNode.Leaf -> {
            leafBuilder(payload)
        }

        is LogicNode.And -> {
            val compiledChildren = children.map { it.compile(leafBuilder) }
            val logic: ConditionLogic = { env ->
                compiledChildren.all { logic -> logic(this, env) }
            }
            logic
        }

        is LogicNode.Or -> {
            val compiledChildren = children.map { it.compile(leafBuilder) }
            val logic: ConditionLogic = { env ->
                compiledChildren.any { logic -> logic(this, env) }
            }
            logic
        }

        is LogicNode.Not -> {
            val compiledChild = child.compile(leafBuilder)
            val logic: ConditionLogic = { env ->
                !compiledChild(this, env)
            }
            logic
        }

        is LogicNode.Branch -> {
            val conditionLogic = leafBuilder(payload)
            val trueLogic = onTrue.compile(leafBuilder)
            val falseLogic = onFalse.compile(leafBuilder)
            val logic: ConditionLogic = { env ->
                if (conditionLogic(this, env)) trueLogic(this, env) else falseLogic(this, env)
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
