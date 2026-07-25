package lin.ui.tree_config.strategy

import lin.rule.tree.EvaluatorPayload
import lin.ui.components.PayloadFactory

class EvaluatorPayloadFactory : PayloadFactory<EvaluatorPayload> {
    override fun createEmptyLeaf(): EvaluatorPayload {
        return EvaluatorPayload.Rule("rule_${System.currentTimeMillis()}")
    }

    override fun createEmptyBranch(): EvaluatorPayload {
        return EvaluatorPayload.BranchCondition("branch_${System.currentTimeMillis()}")
    }

    override fun extractNodeId(payload: EvaluatorPayload): String? {
        return when (payload) {
            is EvaluatorPayload.Rule -> payload.nodeId
            is EvaluatorPayload.BranchCondition -> payload.nodeId
        }
    }
}
