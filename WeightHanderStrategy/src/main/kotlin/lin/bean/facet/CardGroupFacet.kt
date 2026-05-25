package lin.bean.facet

import lin.bean.ComboCard

/**
 * 卡牌分组分面，管理来自 UI 配置的 CardGroupBinding 所生成的卡牌分组 ID 集合。
 * 提供存储与查询能力，避免将分组逻辑散落在 ComboCard 或 MyWarManage 中。
 */
class CardGroupFacet {
    private val _groupIds: MutableSet<String> = linkedSetOf()


    val groupIds: Set<String> get() = _groupIds

    fun add(groupId: String) {
        _groupIds.add(groupId)
    }

    fun addAll(groupIds: Collection<String>) {
        _groupIds.addAll(groupIds)
    }

    fun hasGroup(groupId: String): Boolean = groupId in _groupIds

    fun hasAnyGroup(groupIds: Collection<String>): Boolean = groupIds.any { it in _groupIds }
}

// ComboCard 便捷访问扩展，少量方法不单开文件
fun ComboCard.groupIds(): Set<String> = cardWeightInfo?.groups?.groupIds ?: emptySet()

fun ComboCard.hasAnyGroup(groupIds: Collection<String>): Boolean =
    cardWeightInfo?.groups?.hasAnyGroup(groupIds) ?: false
