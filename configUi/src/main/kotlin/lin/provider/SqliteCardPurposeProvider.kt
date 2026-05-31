package lin.provider

import lin.bean.usePlan.CardPurpose
import lin.card_purpose.db.CardPurposeRepository
import lin.domain.use.plan.CardPurposeProvider

class SqliteCardPurposeProvider(
    private val repository: CardPurposeRepository
) : CardPurposeProvider {
    override fun purposeOf(cardIds: Set<String>): Map<String, CardPurpose> {
        if (cardIds.isEmpty()) return emptyMap()
        return repository.findByCardIds(cardIds).associate { it.cardId to it.toDomain() }
    }
}
