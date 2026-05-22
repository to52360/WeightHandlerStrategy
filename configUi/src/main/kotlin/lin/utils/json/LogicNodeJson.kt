package lin.utils.json

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.ObjectMapper
import lin.rule.tree.LogicNode

fun ObjectMapper.registerLogicNodeMixin(): ObjectMapper {
    return addMixIn(LogicNode::class.java, LogicNodeMixin::class.java)
}

// Use wrapper objects such as {"AndNode": {...}} so persisted trees stay concise.
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_OBJECT)
@JsonSubTypes(
    JsonSubTypes.Type(value = LogicNode.Leaf::class, name = "Leaf"),
    JsonSubTypes.Type(value = LogicNode.And::class, name = "AndNode"),
    JsonSubTypes.Type(value = LogicNode.Or::class, name = "OrNode"),
    JsonSubTypes.Type(value = LogicNode.Not::class, name = "NotNode"),
    JsonSubTypes.Type(value = LogicNode.Branch::class, name = "BranchNode")
)
private abstract class LogicNodeMixin
