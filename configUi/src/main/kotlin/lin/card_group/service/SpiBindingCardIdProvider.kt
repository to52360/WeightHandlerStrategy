package lin.card_group.service

import lin.card_group.repository.CardGroupRepository
import lin.serviceLoader.provider.BindingCardIdProvider
import lin.utils.resolveJdbcProvider

/**
 * 供策略层通过 SPI 获取绑定组反向索引：bindingId -> cardIds。
 */
class SpiBindingCardIdProvider : BindingCardIdProvider {
    private val service by lazy {
        CardGroupService(CardGroupRepository(resolveJdbcProvider().jdbcTemplate))
    }

    override fun provide(): Map<String, List<String>> {
        val index = linkedMapOf<String, MutableList<String>>()
        service.loadAll(onlyEnabled = true)
            .asSequence()
            .flatMap { it.bindings.asSequence() }
            .forEach { binding ->
                index[binding.id] = binding.cardIds.toMutableList()
            }
        return index
    }
}
