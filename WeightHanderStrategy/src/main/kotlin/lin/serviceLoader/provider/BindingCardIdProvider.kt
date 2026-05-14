package lin.serviceLoader.provider

/**
 * 提供绑定组 ID 到 cardId 列表的反向索引。
 * key: CardGroupBinding.id
 * value: 该绑定组下的 cardId 列表
 */
interface BindingCardIdProvider {
    fun provide(): Map<String, List<String>>
}
