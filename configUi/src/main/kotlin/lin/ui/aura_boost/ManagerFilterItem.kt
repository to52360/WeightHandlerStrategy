package lin.ui.aura_boost

/**
 * 卡组方案下拉项。
 * id == null 表示特殊项：列表侧为「全部卡组方案」过滤，编辑器侧为「(全局共享)」。
 */
data class ManagerFilterItem(
    val id: String?,
    val name: String
) {
    override fun toString(): String = name
}
