package lin.rule.condition

data class ConditionTreeConfig(
    val id: String,
    val name: String?,
    val root: ConditionNode
)

interface ConditionTreeConfigProvider {
    fun findById(id: String): ConditionTreeConfig?
    fun findAll(): List<ConditionTreeConfig>
}
