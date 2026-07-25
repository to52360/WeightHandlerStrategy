package lin.ui.condition_tree

data class ConditionTreeListItem(
    val id: String,
    val name: String,
    val isDraft: Boolean = false
) {
    override fun toString(): String = if (isDraft) "* $name (未保存)" else name
}
