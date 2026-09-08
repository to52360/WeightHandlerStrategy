package lin.ui.condition_tree

data class ConditionTreeListItem(
    val id: String,
    val name: String,
    val isDraft: Boolean = false,
    val managerId: String? = null,
    val managerName: String? = null,
    val inlineCreated: Boolean = false
) {
    override fun toString(): String {
        val draftPrefix = if (isDraft) "* " else ""
        val scopeTag = if (managerId.isNullOrEmpty()) "[全局]" else "[私有:${managerName ?: managerId.take(6)}]"
        val inlineTag = if (inlineCreated) "[内联]" else ""
        val draftSuffix = if (isDraft) " (未保存)" else ""
        return "$draftPrefix$scopeTag$inlineTag $name$draftSuffix"
    }
}
