package lin.ui.condition_tree.strategy

import lin.rule.condition.ConditionPayload
import lin.ui.components.PayloadFactory

class ConditionPayloadFactory : PayloadFactory<ConditionPayload> {
    override fun createEmptyLeaf(): ConditionPayload {
        return ConditionPayload.ConditionRef(
            conditionId = "condition_${System.currentTimeMillis()}",
            refId = "condition_${System.currentTimeMillis().toString(16).takeLast(4)}"
        )
    }

    override fun createEmptyBranch(): ConditionPayload {
        return ConditionPayload.ConditionRef(
            conditionId = "branch_${System.currentTimeMillis()}",
            refId = "branch_${System.currentTimeMillis().toString(16).takeLast(4)}"
        )
    }

    override fun extractNodeId(payload: ConditionPayload): String {
        return payload.refId
    }
}
