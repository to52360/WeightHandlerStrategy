package lin.card_group.service

import lin.card_group.repository.CardGroupRepository
import lin.serviceLoader.provider.CardGroupIndexProvider
import lin.utils.resolveJdbcProvider

/**
 * 供策略层通过 SPI 获取卡牌分组反向索引：cardId -> groupIds。
 */
class SpiCardGroupIndexProvider : CardGroupIndexProvider {
    private val service by lazy {
        CardGroupService(CardGroupRepository(resolveJdbcProvider().jdbcTemplate))
    }

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
