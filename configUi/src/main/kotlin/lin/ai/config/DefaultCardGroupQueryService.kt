package lin.ai.config

import lin.dao.CardGroupJsonParser
import lin.ui.db.CardIdNameText
import lin.ui.db.HsCardRepository

/**
 * CardGroupQueryService 的默认实现。
 * 从 .cardgroup 文件读取卡池结构，从 hs.cards 批量查询 name / text。
 * 单一职责：只做卡池查询，不碰评估树或引擎逻辑。
 */
class DefaultCardGroupQueryService(
    private val hsCardRepository: HsCardRepository
) : CardGroupQueryService {

    override fun listCardGroupSources(): List<CardGroupSourceInfo> {
        return CardGroupJsonParser.loadAllCardGroups().map { (fileName, config) ->
            CardGroupSourceInfo(
                fileName = fileName,
                enabled = config.enabled,
                cardCount = config.cards.size
            )
        }
    }

    override fun getCardGroupDetail(fileName: String): CardGroupDetail? {
        val config = CardGroupJsonParser.loadByFileName(fileName) ?: return null
        val cardIds = config.cards.map { it.cardId }
        val cardMap: Map<String, CardIdNameText> = hsCardRepository.findCardByIds(cardIds)
            .associateBy { it.cardId }
        val cards = config.cards.map { weight ->
            val info = cardMap[weight.cardId]
            CardGroupCard(
                cardId = weight.cardId,
                name = info?.name ?: weight.cardId,
                text = info?.text
            )
        }
        return CardGroupDetail(fileName = fileName, cards = cards)
    }
}