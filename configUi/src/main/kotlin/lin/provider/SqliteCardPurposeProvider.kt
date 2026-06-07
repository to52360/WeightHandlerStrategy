package lin.provider

import lin.bean.usePlan.CardPurpose
import lin.card_purpose.db.CardPurposeRepository
import lin.serviceLoader.provider.config.CardPurposeProvider

class SqliteCardPurposeProvider(
    private val repository: CardPurposeRepository
) : CardPurposeProvider {
    override fun findAllEnabled(): Map<String, CardPurpose> {
        return repository.findAll().associate { it.cardId to it.toDomain() }
    }
}
