package lin.card_group.service

import lin.serviceLoader.provider.BindingCardIdProvider

/**
 * 供策略层通过 SPI 获取绑定组反向索引：bindingId -> cardIds。
 */
class SpiBindingCardIdProvider(
    private val service: CardGroupService
) : BindingCardIdProvider {

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
