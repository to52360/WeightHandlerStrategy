package lin.provider

import lin.bean.usePlan.CardPurpose
import lin.card_purpose.db.CardPurposeRepository
import lin.serviceLoader.provider.config.CardPurposeProvider

class SqliteCardPurposeProvider(
    private val repository: CardPurposeRepository
) : CardPurposeProvider {
    override fun findAllEnabled(): Map<String, CardPurpose> {
        return repository.findAll()
            .filter { !it.toDomain().isDefault() }
            .associate { it.cardId to it.toDomain() }
    }

    override fun findByIds(ids: Set<String>): Map<String, CardPurpose> {
        if (ids.isEmpty()) return emptyMap()
        return repository.findByCardIds(ids)
            .filter { !it.toDomain().isDefault() }
            .associate { it.cardId to it.toDomain() }
    }
}
