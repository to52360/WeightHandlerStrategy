package lin.provider

import lin.card_group.service.CardGroupService
import lin.serviceLoader.provider.CardGroupIndexProvider

/**
 * 供策略层通过 SPI 获取卡牌分组反向索引：cardId -> groupIds。
 */
class SpiCardGroupIndexProvider(
    private val service: CardGroupService
) : CardGroupIndexProvider {
    override fun provide(): Map<String, Set<String>> {
        val index = linkedMapOf<String, MutableSet<String>>()
        service.loadAll(onlyEnabled = true)
            .asSequence()
            .flatMap { it.bindings.asSequence() }
            .forEach { binding ->
                binding.cardIds.forEach { cardId ->
                    index.getOrPut(cardId) { linkedSetOf() }.add(binding.id)
                }
            }
        return index.mapValues { (_, groupIds) -> groupIds.toSet() }
    }
}
