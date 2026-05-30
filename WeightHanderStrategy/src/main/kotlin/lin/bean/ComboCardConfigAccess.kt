package lin.bean

/**
 * ComboCard 的配置读取扩展。
 *
 * ComboCard 本体只承担运行时状态容器职责。以后新增 CardCombinedConfig 字段时，
 * 优先在实际消费它的领域目录提供扩展函数；只有跨领域高频使用的读取入口才放这里。
 */
fun ComboCard.groupIds(): Set<String> = combinedConfig?.groupIds ?: emptySet()

fun ComboCard.groupId() = cardWeightInfo?.groupId

fun ComboCard.hasGroup(groupId: String): Boolean = groupId in groupIds()

fun ComboCard.hasAnyGroup(groupIds: Collection<String>): Boolean = groupIds.any { it in this.groupIds() }

fun ComboCard.toDie() = cardWeightInfo?.toDie ?: false
