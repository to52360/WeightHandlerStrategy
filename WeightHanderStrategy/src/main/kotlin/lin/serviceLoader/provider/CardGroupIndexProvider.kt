package lin.serviceLoader.provider

/**
 * 提供运行时卡牌分组索引。
 * key: cardId
 * value: 该卡所属的分组 id 集合
 */
interface CardGroupIndexProvider {
    fun provide(): Map<String, Set<String>>
}
